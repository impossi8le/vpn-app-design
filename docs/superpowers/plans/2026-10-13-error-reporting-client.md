# Логирование и репортинг ошибок — клиентская часть

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Накапливать логи ядра всегда (включая релиз), вырезать из них секреты и уметь отправить обезличенный пакет на MOS — автоматически (метрики) или по кнопке.

**Architecture:** Чистая логика (скраббер, обезличивание, буфер, модели) живёт в `core:domain` без Android и проверяется JVM-тестами. Транспорт — два класса в `core:network` поверх существующего `OkHttp`/`ApiClient`. Периодическая отправка — `WorkManager`. Источник логов — колбэк `onLog` ядра в `vpnservice`.

**Tech Stack:** Kotlin, JUnit 5, kotlinx.serialization, OkHttp 4.12, WorkManager.

## Global Constraints

- Рабочая директория для команд: `D:/VPN_app/android`.
- Тесты: `JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :<module>:test --no-daemon`. JDK 21 не подходит.
- JUnit 5 уже настроен (`useJUnitPlatform()`); новых модулей не заводим.
- **Никогда не заворачивать Gradle в конвейер** — код возврата маскируется, это ложная зелень.
- Секреты (приватный ключ, `tls-auth`/`tls-crypt`, тела PEM, токен сессии, Telegram ID, `device_nonce`) **не попадают ни в буфер, ни в тело запроса**.
- Скраббер применяется **на входе в буфер**, не на выходе.
- Имена тестов в backticks разрешены в `src/test` (JVM); в `src/androidTest` — **только camelCase** (D8 запрещает пробелы до DEX 040).
- Ответ без `code` в теле ошибки клиент трактует как «неизвестная ошибка».
- Комментарии — по-русски, объясняют «почему», а не «что». Стиль — как в `ProfileSanitizer.kt`.

---

### Task 1: LogScrubber — вырезание секретов из строки лога

**Files:**
- Create: `core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/diagnostics/LogScrubber.kt`
- Test: `core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/diagnostics/LogScrubberTest.kt`

**Interfaces:**
- Consumes: ничего.
- Produces: `object LogScrubber { fun scrub(line: String): String }` — возвращает строку без секретов; `fun scrubAll(lines: List<String>): List<String>`.

- [ ] **Step 1: Написать падающий тест**

```kotlin
package com.impossi8le.vpnapp.domain.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Вырезание секретов из строки лога.
 *
 * Логи ядра и сетевого слоя уходят на MOS, поэтому ключевой материал обязан
 * исчезнуть ещё до того, как строка попадёт в буфер. Это тот же класс защиты,
 * что `ProfileSanitizer` для `verb`: приватный ключ в логе — утечка доступа,
 * а не косметический дефект (прецедент — CVE в OpenVPN Connect < 3.5.0).
 */
class LogScrubberTest {

    @Test
    fun `тело PEM вырезается`() {
        val line = "wrote key -----BEGIN PRIVATE KEY-----MIIEvQIBADANBgkq-----END PRIVATE KEY-----"
        val scrubbed = LogScrubber.scrub(line)

        assertFalse(scrubbed.contains("MIIEvQIBADANBgkq"), "тело ключа не должно остаться")
        assertTrue(scrubbed.contains("[REDACTED]"), "на месте секрета обязана быть метка")
    }

    @Test
    fun `многострочный PEM превращается в одну метку`() {
        val log = """
            dumping key:
            -----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBgkqhkiG9w0BAQEFAASC
            AqEAgkEA1234abcd
            -----END PRIVATE KEY-----
            next line
        """.trimIndent()

        val scrubbed = LogScrubber.scrub(log)

        assertFalse(scrubbed.contains("MIIEvQIBADANBgkqhkiG9w0BAQEFAASC"))
        assertFalse(scrubbed.contains("AqEAgkEA1234abcd"))
        assertTrue(scrubbed.contains("next line"), "обычный текст после блока обязан остаться")
    }

    @Test
    fun `static key tls-auth вырезается`() {
        val log = "-----BEGIN OpenVPN Static key V1-----\nabc123\n-----END OpenVPN Static key V1-----"
        val scrubbed = LogScrubber.scrub(log)

        assertFalse(scrubbed.contains("abc123"))
    }

    @Test
    fun `bearer token вырезается`() {
        val scrubbed = LogScrubber.scrub("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig")

        assertFalse(scrubbed.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(scrubbed.contains("Bearer [REDACTED]"))
    }

    @Test
    fun `обычная строка не меняется`() {
        val line = "EVENT: CONNECTED via UDP on tun/10.84.0.42/"

        assertEquals(line, LogScrubber.scrub(line), "нечего скрывать — текст остаётся байт в байт")
    }

    @Test
    fun `scrubAll применяется к каждой строке`() {
        val out = LogScrubber.scrubAll(listOf("ok", "token abc.secret.def", "still ok"))

        assertEquals(listOf("ok", "token abc.secret.def".let { LogScrubber.scrub(it) }, "still ok"), out)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests '*LogScrubberTest' --no-daemon
```

Ожидаемо: `Unresolved reference: LogScrubber`.

- [ ] **Step 3: Написать минимальную реализацию**

```kotlin
package com.impossi8le.vpnapp.domain.diagnostics

/**
 * Удаление секретов из строки лога перед буферизацией.
 *
 * Зачем именно так: логи ядра уходят на приёмный сервер, а ядро при `verb >= 3`
 * печатает тела PEM — то есть приватный ключ. Уровень понижается
 * (`ProfileSanitizer`), но полагаться только на это нельзя: ключ может попасть в
 * лог из сетевого слоя, из дампа окружения или из будущей правки профиля.
 *
 * Поэтому скраббер — вторая линия, и он работает ДО буфера: секрет не должен
 * лежать в памяти устройства вообще, иначе утечёт при дампе или отладке.
 *
 * Что вырезается: блоки PEM (лимитированные BEGIN/END), `Bearer <token>`,
 * `token=`, `device_nonce=`. Что не вырезается: обычный текст лога — он нужен
 * целиком, обрезать его значило бы терять причину сбоя.
 */
object LogScrubber {

    private const val REDACTED = "[REDACTED]"

    private val PEM_BLOCK = Regex(
        "-----BEGIN [A-Z0-9 ]+-----[\\s\\S]*?-----END [A-Z0-9 ]+-----",
        RegexOption.MULTILINE,
    )
    private val BEARER = Regex("(Bearer\\s+)\\S+", RegexOption.IGNORE_CASE)
    private val QUOTED_TOKEN = Regex(
        "(session_token|token|device_nonce)([\"']?\\s*[:=]\\s*[\"']?)([^\\s\"',}]+)",
        RegexOption.IGNORE_CASE,
    )

    fun scrub(line: String): String {
        var result = line.replace(PEM_BLOCK, REDACTED)
        result = result.replace(BEARER) { "${it.groupValues[1]}$REDACTED" }
        result = result.replace(QUOTED_TOKEN) { m ->
            "${m.groupValues[1]}${m.groupValues[2]}$REDACTED"
        }
        return result
    }

    fun scrubAll(lines: List<String>): List<String> = lines.map(::scrub)
}
```

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests '*LogScrubberTest' --no-daemon
```

Ожидаемо: `BUILD SUCCESSFUL`, 6 тестов.

- [ ] **Step 5: Коммит**

```bash
cd /d/VPN_app && git add android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/diagnostics/LogScrubber.kt android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/diagnostics/LogScrubberTest.kt && git commit -m "Вырезать секреты из строк лога перед буферизацией"
```

---

### Task 2: ConfigAnonymizer — обезличивание `.ovpn`

**Files:**
- Create: `core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/diagnostics/ConfigAnonymizer.kt`
- Test: `core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/diagnostics/ConfigAnonymizerTest.kt`

**Interfaces:**
- Consumes: ничего.
- Produces: `object ConfigAnonymizer { fun anonymize(profileText: String): String }` — текст без ключевых блоков.

- [ ] **Step 1: Написать падающий тест**

```kotlin
package com.impossi8le.vpnapp.domain.diagnostics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Обезличивание профиля перед отправкой в отчёте.
 *
 * Профиль содержит приватный ключ и ключ `tls-auth` — это доступ к серверу, а не
 * «настройка». Отправка их на приёмный сервер означала бы передачу доступа
 * третьей стороне, поэтому ключевые блоки вырезаются здесь, на устройстве.
 *
 * Проверяется на боевой фикстуре (`anonymized-production.ovpn`): она повторяет
 * реальный профиль директива в директиву, поэтому тест ловит именно то, что
 * уйдёт на сервер.
 */
class ConfigAnonymizerTest {

    private val productionProfile = """
        client
        dev tun
        proto udp
        remote 192.0.2.10 443 udp
        remote-random
        cipher AES-256-CBC
        data-ciphers AES-256-GCM:AES-128-GCM
        auth SHA256
        resolv-retry infinite
        nobind
        persist-key
        persist-tun
        remote-cert-tls server
        key-direction 1
        verb 1
        redirect-gateway def1 bypass-dhcp
        <ca>
        -----BEGIN CERTIFICATE-----
        MIICertDataAAA
        -----END CERTIFICATE-----
        </ca>
        <cert>
        -----BEGIN CERTIFICATE-----
        MIIClientCertBBB
        -----END CERTIFICATE-----
        </cert>
        <key>
        -----BEGIN PRIVATE KEY-----
        SUPERSECRETKEYDATA
        -----END PRIVATE KEY-----
        </key>
        <tls-auth>
        -----BEGIN OpenVPN Static key V1-----
        abc123statickey
        -----END OpenVPN Static key V1-----
        </tls-auth>
    """.trimIndent()

    @Test
    fun `приватный ключ не остаётся в обезличенном профиле`() {
        val result = ConfigAnonymizer.anonymize(productionProfile)

        assertFalse(result.contains("SUPERSECRETKEYDATA"), "тело приватного ключа — утечка доступа")
        assertFalse(result.contains("abc123statickey"), "ключ tls-auth — тот же класс секрета")
    }

    @Test
    fun `сертификаты не остаются`() {
        val result = ConfigAnonymizer.anonymize(productionProfile)

        assertFalse(result.contains("MIICertDataAAA"))
        assertFalse(result.contains("MIIClientCertBBB"))
    }

    @Test
    fun `структура подключения сохраняется`() {
        val result = ConfigAnonymizer.anonymize(productionProfile)

        assertTrue(result.contains("remote 192.0.2.10 443 udp"), "адрес сервера нужен для диагностики")
        assertTrue(result.contains("proto udp"))
        assertTrue(result.contains("cipher AES-256-CBC"))
        assertTrue(result.contains("data-ciphers AES-256-GCM:AES-128-GCM"))
        assertTrue(result.contains("redirect-gateway def1 bypass-dhcp"), "маршрутизация — частая причина сбоя")
    }

    @Test
    fun `теги блоков тоже убираются, чтобы не выглядело как обрыв`() {
        val result = ConfigAnonymizer.anonymize(productionProfile)

        assertFalse(result.contains("<key>"), "пустой тег выглядел бы как испорченный профиль")
        assertFalse(result.contains("<tls-auth>"))
        assertTrue(result.contains("# <key> удалён"), "факт удаления виден, а не замаскирован")
    }

    @Test
    fun `профиль без ключевых блоков не меняется`() {
        val plain = "client\ndev tun\nremote 192.0.2.10 443\nverb 1\n"
        val result = ConfigAnonymizer.anonymize(plain)

        assertTrue(result.contains("dev tun"))
        assertTrue(result.contains("remote 192.0.2.10 443"))
        assertFalse(result.contains("удалён"), "нечего удалять — метки быть не должно")
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests '*ConfigAnonymizerTest' --no-daemon
```

Ожидаемо: `Unresolved reference: ConfigAnonymizer`.

- [ ] **Step 3: Написать реализацию**

```kotlin
package com.impossi8le.vpnapp.domain.diagnostics

/**
 * Обезличивание профиля перед отправкой в диагностическом отчёте.
 *
 * Профиль `.ovpn` — недоверенный вход, но в нём есть и полезное: адрес сервера,
 * шифры, маршрутизация. По ним видно причину сбоя (`tun_prop_error` часто следует
 * из несовместимого шифра или конфликта маршрутов), а по ключам — ничего, кроме
 * доступа к серверу.
 *
 * Поэтому вырезаются ровно блоки ключевого материала (`<ca>`, `<cert>`, `<key>`,
 * `<tls-auth>`, `<tls-crypt>`), а остальные директивы остаются. Теги удаляются
 * вместе с содержимым: пустой `<key></key>` читался бы как испорченный профиль.
 *
 * Это чистая функция без Android — её проверяют на JVM, с боевой фикстурой.
 */
object ConfigAnonymizer {

    /** Блоки, целиком подлежащие удалению: ключи и сертификаты. */
    private val SENSITIVE_TAGS = listOf("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2")

    fun anonymize(profileText: String): String {
        val lines = profileText.split('\n')
        val result = ArrayList<String>(lines.size)
        var removedTag: String? = null

        for (line in lines) {
            val trimmed = line.trim()

            if (removedTag != null) {
                // Мы внутри вырезаемого блока — ждём закрывающий тег.
                if (trimmed.equals("</$removedTag>", ignoreCase = true)) {
                    result += "# <$removedTag> удалён (ключевой материал)"
                    removedTag = null
                }
                continue
            }

            val opening = openingSensitiveTag(trimmed)
            if (opening != null) {
                removedTag = opening
                continue
            }

            result += line
        }

        return result.joinToString("\n")
    }

    /** Имя открывающего чувствительного тега, если строка — именно он. */
    private fun openingSensitiveTag(trimmed: String): String? {
        if (!trimmed.startsWith("<") || trimmed.startsWith("</")) return null
        val name = trimmed.removePrefix("<").substringBefore('>').trim().lowercase()
        return name.takeIf { it in SENSITIVE_TAGS }
    }
}
```

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests '*ConfigAnonymizerTest' --no-daemon
```

Ожидаемо: `BUILD SUCCESSFUL`, 5 тестов.

- [ ] **Step 5: Коммит**

```bash
cd /d/VPN_app && git add android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/diagnostics/ConfigAnonymizer.kt android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/diagnostics/ConfigAnonymizerTest.kt && git commit -m "Вырезать ключевой материал из профиля перед отправкой"
```

---

### Task 3: LogBuffer — кольцевой буфер с фильтром на входе

**Files:**
- Create: `core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/diagnostics/LogBuffer.kt`
- Test: `core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/diagnostics/LogBufferTest.kt`

**Interfaces:**
- Consumes: `LogScrubber.scrub`.
- Produces: `class LogBuffer(maxLines: Int = 2000, maxBytes: Int = 262144) { fun add(line: String); fun snapshot(): List<String>; fun size(): Int; fun clear() }`.

- [ ] **Step 1: Написать падающий тест**

```kotlin
package com.impossi8le.vpnapp.domain.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Кольцевой буфер логов.
 *
 * Логи пишутся ВСЕГДА, включая релиз: иначе в релизной сборке собирать нечего, и
 * причина сбоя на чужом телефоне остаётся невидимой (сейчас логи стоят под
 * `BuildConfig.DEBUG`). Буфер в памяти ограничен, поэтому старые строки
 * вытесняются — иначе долгая сессия съест память.
 *
 * Скраббер применяется здесь, на входе: секрет не должен попасть в буфер вообще.
 */
class LogBufferTest {

    @Test
    fun `строки накапливаются в порядке добавления`() {
        val buffer = LogBuffer()
        buffer.add("первая")
        buffer.add("вторая")

        assertEquals(listOf("первая", "вторая"), buffer.snapshot())
    }

    @Test
    fun `при превышении лимита вытесняется самое старое`() {
        val buffer = LogBuffer(maxLines = 3)
        repeat(5) { buffer.add("строка $it") }

        assertEquals(listOf("строка 2", "строка 3", "строка 4"), buffer.snapshot())
    }

    @Test
    fun `секрет не попадает в буфер`() {
        val buffer = LogBuffer()
        buffer.add("key: -----BEGIN PRIVATE KEY-----SECRETDATA-----END PRIVATE KEY-----")

        val snapshot = buffer.snapshot().joinToString("\n")
        assertFalse(snapshot.contains("SECRETDATA"), "скраббер обязан работать на входе, а не на выходе")
        assertTrue(snapshot.contains("[REDACTED]"))
    }

    @Test
    fun `лимит по байтам тоже вытесняет`() {
        val buffer = LogBuffer(maxLines = 1000, maxBytes = 50)
        buffer.add("a".repeat(30))
        buffer.add("b".repeat(30))

        val snapshot = buffer.snapshot()
        assertTrue(snapshot.size < 2, "две строки по 30 байт не влезают в 50 байт")
        assertTrue(snapshot.any { it.startsWith("b") }, "свежая строка обязана остаться")
    }

    @Test
    fun `clear опустошает буфер`() {
        val buffer = LogBuffer()
        buffer.add("что-то")
        buffer.clear()

        assertEquals(0, buffer.size())
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests '*LogBufferTest' --no-daemon
```

Ожидаемо: `Unresolved reference: LogBuffer`.

- [ ] **Step 3: Написать реализацию**

```kotlin
package com.impossi8le.vpnapp.domain.diagnostics

/**
 * Ограниченный буфер логов в памяти.
 *
 * Два лимита, а не один: строки ядра бывают короткими, а дампы большими, поэтому
 * потолок по количеству защищает от тысяч строк, а по байтам — от одной огромной.
 * Срабатывает тот, что наступит раньше.
 *
 * Не потокобезопасен намеренно: вызывается из одного места (колбэк ядра), а
 * синхронизацию добавляет владелец, если понадобится второй писатель. Примитив
 * без нужды усложнять не стоит.
 */
class LogBuffer(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
) {
    private val lines = ArrayDeque<String>()
    private var bytes = 0

    fun add(line: String) {
        val scrubbed = LogScrubber.scrub(line)
        lines.addLast(scrubbed)
        bytes += scrubbed.length
        trim()
    }

    fun snapshot(): List<String> = lines.toList()

    fun size(): Int = lines.size

    fun clear() {
        lines.clear()
        bytes = 0
    }

    private fun trim() {
        while (lines.size > maxLines || (bytes > maxBytes && lines.size > 1)) {
            val removed = lines.removeFirst()
            bytes -= removed.length
        }
    }

    companion object {
        const val DEFAULT_MAX_LINES = 2000
        const val DEFAULT_MAX_BYTES = 262144 // 256 КБ
    }
}
```

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests '*LogBufferTest' --no-daemon
```

Ожидаемо: `BUILD SUCCESSFUL`, 5 тестов.

- [ ] **Step 5: Коммит**

```bash
cd /d/VPN_app && git add android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/diagnostics/LogBuffer.kt android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/diagnostics/LogBufferTest.kt && git commit -m "Добавить кольцевой буфер логов с фильтром секретов на входе"
```

---

### Task 4: ReportModel и ReportQueue — что уходит и очередь недоотправленного

**Files:**
- Create: `core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/diagnostics/ReportModel.kt`
- Create: `core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/diagnostics/ReportQueue.kt`
- Test: `core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/diagnostics/ReportModelTest.kt`

**Interfaces:**
- Consumes: ничего.
- Produces:
  - `data class TelemetryEvent(eventId, type, ts, state, configId, configName, server, durationMs, code)`
  - `enum class EventType { StateChange, Error, ConnectAttempt }`
  - `interface ReportQueue { suspend fun enqueue(json: String); suspend fun peek(): List<String>; suspend fun remove(ids: List<String>); suspend fun size(): Int; suspend fun clear() }`

- [ ] **Step 1: Написать падающий тест**

```kotlin
package com.impossi8le.vpnapp.domain.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Модель события телеметрии и её сериализация.
 *
 * Граница приватности задана ТИПОМ, а не дисциплиной: в [TelemetryEvent] нет
 * полей для токена, ключа или `device_nonce` — их некуда положить. Это тот же
 * приём, что в `DiagnosticsInput`: границу видно по списку полей, и тест может
 * доказать отсутствие утечки, а не полагаться на «вроде не передавали».
 */
class ReportModelTest {

    private val json = Json { encodeDefaults = false }

    @Test
    fun `сериализованное событие не содержит секретов`() {
        val event = TelemetryEvent(
            eventId = "0f9c",
            type = EventType.StateChange,
            ts = "2026-10-13T09:20:11Z",
            state = "connected",
            configId = "LVclient193",
            configName = "Нидерланды",
            server = "192.0.2.10:1194",
            durationMs = 4210,
            code = null,
        )
        val text = json.encodeToString(TelemetryEvent.serializer(), event)

        assertFalse(text.contains("Bearer"), "заголовку авторизации не место в событии")
        assertFalse(text.contains("PRIVATE KEY"))
        assertTrue(text.contains("LVclient193"), "id подключения — часть диагностики по решению владельца")
        assertTrue(text.contains("192.0.2.10:1194"), "адрес сервера тоже входит в авто-метрики")
    }

    @Test
    fun `eventId нужен для идемпотентности и всегда есть`() {
        val event = TelemetryEvent(
            eventId = "abc-123",
            type = EventType.Error,
            ts = "2026-10-13T09:20:11Z",
            state = "failed",
            configId = null,
            configName = null,
            server = null,
            durationMs = null,
            code = "tun_prop_error",
        )
        val text = json.encodeToString(TelemetryEvent.serializer(), event)

        assertTrue(text.contains("\"event_id\":\"abc-123\""))
        assertTrue(text.contains("\"code\":\"tun_prop_error\""))
    }

    @Test
    fun `поля имён в JSON — snake_case по контракту`() {
        val event = TelemetryEvent(
            eventId = "e1", type = EventType.ConnectAttempt, ts = "t", state = null,
            configId = null, configName = null, server = null, durationMs = 100, code = null,
        )
        val obj = Json.parseToJsonElement(
            json.encodeToString(TelemetryEvent.serializer(), event),
        ).jsonObject

        assertTrue(obj.containsKey("event_id"))
        assertTrue(obj.containsKey("duration_ms"))
        assertEquals("connect_attempt", obj["type"]!!.jsonPrimitive.content)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests '*ReportModelTest' --no-daemon
```

Ожидаемо: `Unresolved reference: TelemetryEvent`.

- [ ] **Step 3: Написать модель и порт очереди**

`ReportModel.kt`:

```kotlin
package com.impossi8le.vpnapp.domain.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Тип события телеметрии.
 *
 * Три вида, а не общий «лог»: по `state_change` видно, где рвётся туннель, по
 * `error` — с каким кодом, по `connect_attempt` — сколько занимает подключение.
 * Разная форма события позволяет серверу строить статистику, не разбирая текст.
 */
@Serializable
enum class EventType {
    @SerialName("state_change") StateChange,
    @SerialName("error") Error,
    @SerialName("connect_attempt") ConnectAttempt,
}

/**
 * Одно событие авто-метрики.
 *
 * **Граница приватности.** Полей для секретов здесь нет: токен, ключ и
 * `device_nonce` физически некуда положить. `configId`, `configName` и `server`
 * включены осознанно (решение владельца, 2026-10-13): сервер и так видит
 * подключения, а для отладки это главные подсказки.
 */
@Serializable
data class TelemetryEvent(
    @SerialName("event_id") val eventId: String,
    @SerialName("type") val type: EventType,
    @SerialName("ts") val ts: String,
    @SerialName("state") val state: String? = null,
    @SerialName("config_id") val configId: String? = null,
    @SerialName("config_name") val configName: String? = null,
    @SerialName("server") val server: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("code") val code: String? = null,
)
```

`ReportQueue.kt`:

```kotlin
package com.impossi8le.vpnapp.domain.diagnostics

/**
 * Очередь недоотправленного.
 *
 * Порт, а не реализация: домену всё равно, файл это или база, а тесты подставляют
 * фейк без Android. Задача очереди — выжить перезапуск: отправка идёт в фоне, и
 * приложение может закрыться между попытками.
 *
 * Элемент — уже сериализованный JSON: транспорт шлёт батч как есть и не зависит
 * от модели.
 */
interface ReportQueue {
    suspend fun enqueue(json: String)
    suspend fun peek(): List<String>
    suspend fun remove(ids: List<String>)
    suspend fun size(): Int

    /**
     * Очистка целиком — на случай `401`. Без токена отправить нечего, а копить
     * события чужого пользователя после выхода из аккаунта нельзя.
     */
    suspend fun clear()
}
```

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests '*ReportModelTest' --no-daemon
```

Ожидаемо: `BUILD SUCCESSFUL`, 3 теста.

- [ ] **Step 5: Коммит**

```bash
cd /d/VPN_app && git add android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/diagnostics/ReportModel.kt android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/diagnostics/ReportQueue.kt android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/diagnostics/ReportModelTest.kt && git commit -m "Добавить модель события телеметрии с границей приватности в типе"
```

---

### Task 5: FileReportQueue — очередь на файле

**Files:**
- Create: `core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/diagnostics/FileReportQueue.kt`
- Test: `core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/diagnostics/FileReportQueueTest.kt`

**Interfaces:**
- Consumes: `ReportQueue`.
- Produces: `class FileReportQueue(file: File, maxEntries: Int = 500) : ReportQueue`.

- [ ] **Step 1: Написать падающий тест**

```kotlin
package com.impossi8le.vpnapp.domain.diagnostics

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Файловая очередь недоотправленного.
 *
 * Файл, а не память: приложение закрывается между попытками отправки, и событие,
 * оставшееся в памяти, потерялось бы. Ограничение по количеству — чтобы очередь
 * не росла бесконечно, если сеть недоступна неделю.
 */
class FileReportQueueTest {

    @Test
    fun `положил и прочитал обратно`() = runBlocking {
        val dir = createTempDir()
        val queue = FileReportQueue(File(dir, "queue.jsonl"))
        queue.enqueue("""{"event_id":"a"}""")
        queue.enqueue("""{"event_id":"b"}""")

        assertEquals(listOf("""{"event_id":"a"}""", """{"event_id":"b"}"""), queue.peek())
    }

    @Test
    fun `переживает пересоздание`() = runBlocking {
        val dir = createTempDir()
        val file = File(dir, "queue.jsonl")
        FileReportQueue(file).enqueue("""{"event_id":"a"}""")

        val reopened = FileReportQueue(file)
        assertEquals(1, reopened.size(), "очередь обязана читаться с диска")
    }

    @Test
    fun `remove убирает отправленное`() = runBlocking {
        val dir = createTempDir()
        val queue = FileReportQueue(File(dir, "queue.jsonl"))
        queue.enqueue("""{"event_id":"a"}""")
        queue.enqueue("""{"event_id":"b"}""")

        queue.remove(listOf("a"))

        assertEquals(listOf("""{"event_id":"b"}"""), queue.peek())
    }

    @Test
    fun `очередь не растёт бесконечно`() = runBlocking {
        val dir = createTempDir()
        val queue = FileReportQueue(File(dir, "queue.jsonl"), maxEntries = 3)
        repeat(5) { queue.enqueue("""{"event_id":"$it"}""") }

        assertEquals(3, queue.size(), "старые обязаны вытесняться")
        assertTrue(queue.peek().last().contains("\"4\""), "самое свежее обязано остаться")
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests '*FileReportQueueTest' --no-daemon
```

Ожидаемо: `Unresolved reference: FileReportQueue`.

- [ ] **Step 3: Написать реализацию**

```kotlin
package com.impossi8le.vpnapp.domain.diagnostics

import java.io.File

/**
 * Очередь на JSONL-файле.
 *
 * Формат — одна строка на событие: дописывание дешёвое, а частично записанная
 * строка отбрасывается при чтении, поэтому обрыв питания не рождает битый файл,
 * из-за которого очередь не прочитается вовсе.
 *
 * Вытеснение — по количеству строк, а не по времени: `maxEntries` проще и
 * предсказуемее, а срок хранения задаёт сервер.
 */
class FileReportQueue(
    private val file: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : ReportQueue {

    override suspend fun enqueue(json: String) {
        writeAll((readAll() + json).takeLast(maxEntries))
    }

    override suspend fun peek(): List<String> = readAll()

    override suspend fun remove(ids: List<String>) {
        if (ids.isEmpty()) return
        val wanted = ids.toSet()
        writeAll(readAll().filterNot { line -> idOf(line) in wanted })
    }

    override suspend fun size(): Int = readAll().size

    override suspend fun clear() {
        writeAll(emptyList())
    }

    private fun readAll(): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    /**
     * Пишем через временный файл: обрыв посреди записи не должен оставить очередь
     * пустой. Атомарный переезд — тот же приём, что в `FileProfileStore`.
     */
    private fun writeAll(lines: List<String>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(lines.joinToString("\n"))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    /** Достать `event_id` из строки очереди; неизвестное значение вытеснять нельзя. */
    private fun idOf(line: String): String? =
        Regex("\"event_id\"\\s*:\\s*\"([^\"]+)\"").find(line)?.groupValues?.get(1)

    companion object {
        const val DEFAULT_MAX_ENTRIES = 500
    }
}
```

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests '*FileReportQueueTest' --no-daemon
```

Ожидаемо: `BUILD SUCCESSFUL`, 4 теста.

- [ ] **Step 5: Коммит**

```bash
cd /d/VPN_app && git add android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/diagnostics/FileReportQueue.kt android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/diagnostics/FileReportQueueTest.kt && git commit -m "Добавить файловую очередь недоотправленных событий"
```

---

### Task 6: TelemetryApi — отправка метрик

**Files:**
- Create: `core/network/src/main/kotlin/com/impossi8le/vpnapp/network/TelemetryApi.kt`
- Test: `core/network/src/test/kotlin/com/impossi8le/vpnapp/network/TelemetryApiTest.kt`

**Interfaces:**
- Consumes: `ApiClient` (`baseUrl`, `http`, `sessionToken`), `TelemetryEvent`.
- Produces:
  - `sealed interface TelemetryResult { object Accepted; object NotAuthorized; object Retry; object Rejected }`
  - `class TelemetryApi(client: ApiClient, platform: String = PLATFORM_ANDROID) { suspend fun send(appVersion: Int, events: List<TelemetryEvent>): TelemetryResult }`

- [ ] **Step 1: Написать падающий тест**

```kotlin
package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.diagnostics.EventType
import com.impossi8le.vpnapp.domain.diagnostics.TelemetryEvent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Отправка батча метрик.
 *
 * Различение ответов проверяется тестом, а не «на глаз»: `401` означает «токена
 * нет» и требует выбросить батч, `422` — «тело не принято» и повторять
 * бессмысленно, `429`/`5xx` — «повторить позже». Слить их в одно «не получилось»
 * значило бы либо терять данные, либо бесконечно долбить сервер негодным телом.
 */
class TelemetryApiTest {

    private fun event(id: String) = TelemetryEvent(
        eventId = id, type = EventType.StateChange, ts = "2026-10-13T09:20:11Z",
        state = "connected", configId = "c1", configName = "Нидерланды",
        server = "192.0.2.10:1194", durationMs = 100, code = null,
    )

    @Test
    fun `204 — принято`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        val api = TelemetryApi(ApiClient(server.url("/").toString()))

        val result = api.send(appVersion = 102, events = listOf(event("a")))

        assertEquals(TelemetryResult.Accepted, result)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/telemetry/events", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"event_id\":\"a\""))
        server.shutdown()
    }

    @Test
    fun `401 — нет авторизации`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        val api = TelemetryApi(ApiClient(server.url("/").toString()))

        assertEquals(TelemetryResult.NotAuthorized, api.send(102, listOf(event("a"))))
        server.shutdown()
    }

    @Test
    fun `422 — тело не принято, повторять бессмысленно`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(422))
        server.start()
        val api = TelemetryApi(ApiClient(server.url("/").toString()))

        assertEquals(TelemetryResult.Rejected, api.send(102, listOf(event("a"))))
        server.shutdown()
    }

    @Test
    fun `429 и 500 — повторить позже`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        val api = TelemetryApi(ApiClient(server.url("/").toString()))

        assertEquals(TelemetryResult.Retry, api.send(102, listOf(event("a"))))
        assertEquals(TelemetryResult.Retry, api.send(102, listOf(event("a"))))
        server.shutdown()
    }

    @Test
    fun `токен сессии уходит в заголовке`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        val client = ApiClient(server.url("/").toString())
        client.sessionToken = "session-abc"
        val api = TelemetryApi(client)

        api.send(102, listOf(event("a")))

        assertEquals("Bearer session-abc", server.takeRequest().getHeader("Authorization"))
        server.shutdown()
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:network:test --tests '*TelemetryApiTest' --no-daemon
```

Ожидаемо: `Unresolved reference: TelemetryApi`.

- [ ] **Step 3: Добавить зависимость на домен и сериализацию в `core:network`**

Проверить `core/network/build.gradle.kts`: там уже есть `project(":core:domain")` и
`serialization-json`? Если нет — добавить в `dependencies`:

```kotlin
implementation(project(":core:domain"))
implementation(libs.serialization.json)
testImplementation(libs.okhttp.mockwebserver)
testImplementation(libs.coroutines.test)
```

Точный набор тестовых зависимостей — по образцу `BypassApi`-тестов в том же модуле.

- [ ] **Step 4: Написать реализацию**

```kotlin
package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.diagnostics.TelemetryEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Исход отправки батча. `Retry` — единственный, при котором батч остаётся в очереди. */
sealed interface TelemetryResult {
    /** `204`: принято, из очереди можно удалять. */
    data object Accepted : TelemetryResult

    /** `401`: токена нет — очередь чистим, слать нечего. */
    data object NotAuthorized : TelemetryResult

    /** `4xx`: тело не принято, повтор не поможет — батч отбрасываем. */
    data object Rejected : TelemetryResult

    /** `429`, `5xx`, сеть: оставляем в очереди и повторяем. */
    data object Retry : TelemetryResult
}

/**
 * Отправка метрик на `POST /api/v1/telemetry/events`.
 *
 * Контракт: батч в одну сторону, ответ `204` — всё, что клиенту нужно. Поэтому
 * тело ответа не читается вовсе: разбирать нечего, а ошибка разбора не должна
 * превращать успешную отправку в неудачную.
 *
 * Стиль повторяет [BypassApi]: `CancellationException` пробрасывается ДО общего
 * `catch`, неудача деградирует в типизированный исход.
 */
class TelemetryApi(
    private val client: ApiClient,
    private val platform: String = PLATFORM_ANDROID,
    private val json: Json = Json { encodeDefaults = false },
) {

    suspend fun send(appVersion: Int, events: List<TelemetryEvent>): TelemetryResult =
        withContext(Dispatchers.IO) {
            try {
                val payload = buildJsonObject {
                    put("app_version", JsonPrimitive(appVersion))
                    put("platform", JsonPrimitive(platform))
                    put("events", json.encodeToJsonElement(ListSerializer(TelemetryEvent.serializer()), events))
                }
                val request = Request.Builder()
                    .url("${client.baseUrl}/telemetry/events")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .apply { client.sessionToken?.let { header("Authorization", "Bearer $it") } }
                    .build()
                client.http.newCall(request).execute().use { response ->
                    when (response.code) {
                        204, 200, 202 -> TelemetryResult.Accepted
                        401, 403 -> TelemetryResult.NotAuthorized
                        in 400..499 -> TelemetryResult.Rejected
                        else -> TelemetryResult.Retry
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                TelemetryResult.Retry
            }
        }
}

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
```

- [ ] **Step 5: Запустить тест и убедиться, что он проходит**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:network:test --tests '*TelemetryApiTest' --no-daemon
```

Ожидаемо: `BUILD SUCCESSFUL`, 5 тестов.

- [ ] **Step 6: Коммит**

```bash
cd /d/VPN_app && git add android/core/network/ && git commit -m "Добавить отправку метрик с различением исходов по коду ответа"
```

---

### Task 7: ReportApi — отправка полного отчёта

**Files:**
- Create: `core/network/src/main/kotlin/com/impossi8le/vpnapp/network/ReportApi.kt`
- Test: `core/network/src/test/kotlin/com/impossi8le/vpnapp/network/ReportApiTest.kt`

**Interfaces:**
- Consumes: `ApiClient`.
- Produces:
  - `sealed interface ReportResult { data class Sent(val reportId: String?); data object NotAuthorized; data object TooLarge; data class Retry(reason: String) }`
  - `class ReportApi(client: ApiClient) { suspend fun send(report: String, logs: String?, config: String?, meta: String): ReportResult }`

- [ ] **Step 1: Написать падающий тест**

```kotlin
package com.impossi8le.vpnapp.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Отправка полного отчёта.
 *
 * `report_id` читается из ответа не для красоты: по нему поддержка находит отчёт,
 * и клиент показывает его пользователю. Но отсутствие поля не должно превращать
 * успешную отправку в неудачу — сервер вправе ответить `204`.
 */
class ReportApiTest {

    @Test
    fun `201 с report_id — отправлено`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"report_id":"r-42"}"""))
        server.start()
        val api = ReportApi(ApiClient(server.url("/").toString()))

        val result = api.send(report = "текст отчёта", logs = "лог", config = null, meta = "{}")

        assertEquals(ReportResult.Sent("r-42"), result)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/report", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("текст отчёта"))
        server.shutdown()
    }

    @Test
    fun `204 без тела — тоже отправлено`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        val api = ReportApi(ApiClient(server.url("/").toString()))

        val result = api.send("отчёт", null, null, "{}")

        assertTrue(result is ReportResult.Sent)
        assertEquals(null, (result as ReportResult.Sent).reportId, "тела нет — id неизвестен, но это успех")
        server.shutdown()
    }

    @Test
    fun `401 — нужен вход`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        val api = ReportApi(ApiClient(server.url("/").toString()))

        assertEquals(ReportResult.NotAuthorized, api.send("отчёт", null, null, "{}"))
        server.shutdown()
    }

    @Test
    fun `413 — отчёт слишком большой`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(413))
        server.start()
        val api = ReportApi(ApiClient(server.url("/").toString()))

        assertEquals(ReportResult.TooLarge, api.send("отчёт", "лог", null, "{}"))
        server.shutdown()
    }

    @Test
    fun `сеть недоступна — повторить позже`() = runTest {
        val api = ReportApi(ApiClient("http://127.0.0.1:1/"))

        val result = api.send("отчёт", null, null, "{}")

        assertNotNull((result as? ReportResult.Retry)?.reason)
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:network:test --tests '*ReportApiTest' --no-daemon
```

Ожидаемо: `Unresolved reference: ReportApi`.

- [ ] **Step 3: Написать реализацию**

```kotlin
package com.impossi8le.vpnapp.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Исход отправки отчёта. */
sealed interface ReportResult {
    /** Успех. [reportId] может быть `null`, если сервер ответил без тела. */
    data class Sent(val reportId: String?) : ReportResult

    /** `401`: сессия истекла. Отчёт сохраняем локально. */
    data object NotAuthorized : ReportResult

    /** `413`: слишком большой — предлагаем вариант без логов. */
    data object TooLarge : ReportResult

    /** Сеть, `429`, `5xx`: отчёт остаётся в локальной очереди. */
    data class Retry(val reason: String) : ReportResult
}

/**
 * Отправка отчёта на `POST /api/v1/report`.
 *
 * `multipart`, а не JSON: логи и конфиг — файлы, а base64 раздул бы тело на треть
 * без выгоды. Отсутствующие части не добавляются вовсе (пустая часть читалась бы
 * как «файл есть, но пустой»).
 *
 * `report_id` парсится защищённо: `runCatching` вокруг разбора, потому что
 * кривое тело ответа не должно превращать доставленный отчёт в недоставленный.
 */
class ReportApi(
    private val client: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    suspend fun send(
        report: String,
        logs: String?,
        config: String?,
        meta: String,
    ): ReportResult = withContext(Dispatchers.IO) {
        try {
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("report", report, report.toRequestBody(TEXT_MEDIA))
                .addFormDataPart("meta", meta, meta.toRequestBody(JSON_MEDIA))
            logs?.let { builder.addFormDataPart("logs", "logs.txt", it.toRequestBody(TEXT_MEDIA)) }
            config?.let { builder.addFormDataPart("config", "profile.ovpn", it.toRequestBody(TEXT_MEDIA)) }

            val request = Request.Builder()
                .url("${client.baseUrl}/report")
                .post(builder.build())
                .apply { client.sessionToken?.let { header("Authorization", "Bearer $it") } }
                .build()

            client.http.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> ReportResult.NotAuthorized
                    response.code == 413 -> ReportResult.TooLarge
                    response.isSuccessful -> ReportResult.Sent(parseReportId(response.body?.string()))
                    response.code == 429 -> ReportResult.Retry("rate_limited")
                    response.code in 500..599 -> ReportResult.Retry("server_error")
                    // Прочие 4xx — тело негодно, повтор не поможет; показываем как
                    // «не отправлено» и оставляем в очереди, чтобы не потерять.
                    else -> ReportResult.Retry("http_${response.code}")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ReportResult.Retry(e.message ?: "network")
        }
    }

    private fun parseReportId(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["report_id"]?.jsonPrimitive?.content
        }.getOrNull()
    }
}

private val TEXT_MEDIA = "text/plain; charset=utf-8".toMediaType()
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
```

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:network:test --tests '*ReportApiTest' --no-daemon
```

Ожидаемо: `BUILD SUCCESSFUL`, 5 тестов.

- [ ] **Step 5: Коммит**

```bash
cd /d/VPN_app && git add android/core/network/ && git commit -m "Добавить отправку полного отчёта multipart с разбором исходов"
```

---

### Task 8: Буфер логов в VpnTunnelService — писать логи всегда

**Files:**
- Modify: `vpnservice/src/main/kotlin/com/impossi8le/vpnapp/vpnservice/VpnTunnelService.kt` (около строки 229)
- Test: `core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/diagnostics/LogBufferScrubIntegrationTest.kt`

**Interfaces:**
- Consumes: `LogBuffer`.
- Produces: логи ядра доступны через `LogBuffer` (общий экземпляр в `AppGraph`); `logcat`-вывод остаётся под `BuildConfig.DEBUG`.

- [ ] **Step 1: Написать падающий тест на интеграцию скраббера и буфера**

Отдельный тест здесь нужен, потому что это единственное место, где логи
**реального** ядра (с дампами) проходят через буфер: проверяем, что дамп PEM в
стиле ядра не оставляет ключа.

```kotlin
package com.impossi8le.vpnapp.domain.diagnostics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Логи ядра в стиле реального вывода проходят через буфер без утечки.
 *
 * Ядро при высоком `verb` печатает тела PEM в составе строки лога. Тест повторяет
 * эту форму, чтобы буфер гарантировал отсутствие ключа даже если `verb` однажды
 * снова поднимут.
 */
class LogBufferScrubIntegrationTest {

    @Test
    fun `дамп ключа ядра не оставляет секрета в буфере`() {
        val buffer = LogBuffer()
        buffer.add("2026-10-13 09:20:11 WARNING: 'link-mtu' is used inconsistently")
        buffer.add("-----BEGIN OpenVPN Static key V1-----")
        buffer.add("deadbeefdeadbeefdeadbeefdeadbeef")
        buffer.add("-----END OpenVPN Static key V1-----")
        buffer.add("2026-10-13 09:20:12 TUN Error: tun_prop_error: route conflict")

        val text = buffer.snapshot().joinToString("\n")

        assertFalse(text.contains("deadbeef"), "ключ tls-auth не должен остаться")
        assertFalse(text.contains("BEGIN OpenVPN Static key"), "маркер блока тоже скрываем")
        assertTrue(text.contains("tun_prop_error"), "причина сбоя обязана остаться — ради неё всё и делается")
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests '*LogBufferScrubIntegrationTest' --no-daemon
```

Ожидаемо: падение на `assertFalse(text.contains("deadbeef"))` — потому что
однострочный PEM-блок из трёх строк скраббер не поймает (он ищет блок в одной
строке). Это **настоящая дыра**, и тест её вскрывает.

- [ ] **Step 3: Починить скраббер — склеивать многострочные блоки**

В `LogScrubber` добавить метод для потока строк, который склеивает PEM через
строки перед разбором:

```kotlin
    /**
     * Скраб потока строк с учётом МНОГОСТРОЧНЫХ блоков PEM.
     *
     * Ядро печатает `BEGIN`/тело/`END` тремя отдельными строками, поэтому
     * построчный скраб их не поймает: ключ утечёт построчно. Склеиваем в окне и
     * режем блок целиком — это тот же класс ошибки, что дубликат `verb`, который
     * «обходил» правку одной строки.
     */
    fun scrubStream(lines: List<String>): List<String> {
        val joined = lines.joinToString("\n")
        val scrubbed = scrub(joined)
        return scrubbed.split("\n")
    }
```

И в `LogBuffer.add` вызывать накопление так, чтобы буфер хранил слитые строки:
заменить хранение построчно на хранение «как пришло», а склейку делать в
`snapshot()`:

```kotlin
    fun add(line: String) {
        val scrubbed = LogScrubber.scrub(line)
        raw.addLast(scrubbed)
        bytes += scrubbed.length
        trim()
    }

    /**
     * Отдаём лог после склейки многострочных блоков: только здесь видно, что
     * `BEGIN` и `END` — части одного секрета.
     */
    fun snapshot(): List<String> = LogScrubber.scrubStream(raw.toList())
```

Переименовать поле `lines` в `raw` по всему телу класса.

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests '*LogBuffer*' --no-daemon
```

Ожидаемо: `BUILD SUCCESSFUL` — и `LogBufferTest`, и новый тест.

- [ ] **Step 5: Править сервис — писать в буфер, а не только в logcat**

В `VpnTunnelService.kt` заменить обработчик `onLog` (строка ~229):

```kotlin
            // Логи ядра идут в буфер ВСЕГДА, включая релиз: иначе в релизной
            // сборке собирать нечего, и причина сбоя на чужом телефоне остаётся
            // невидимой. В logcat — только в отладке: строки содержат адреса
            // серверов и имена профилей. Приватного ключа там не будет: `verb`
            // понижен до 1, а PEM печатаются начиная с 3, и вторая линия —
            // LogScrubber на входе буфера.
            newSession.onLog = { line ->
                logBuffer.add(line)
                if (BuildConfig.DEBUG) android.util.Log.d(TAG, line)
            }
```

`logBuffer` — поле сервиса, получаемое через интент/граф: передать экземпляр
`LogBuffer` из `AppGraph` так же, как передаётся путь профиля. Если сервис не
имеет доступа к графу, завести в сервисе собственный `LogBuffer` и отдавать его
наружу статическим аксессором — но **предпочтителен общий из графа**, иначе
буфер сервиса не увидит экран.

- [ ] **Step 6: Собрать и прогнать тесты сервиса**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :vpnservice:testDebugUnitTest :core:domain:test --no-daemon
```

Ожидаемо: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Коммит**

```bash
cd /d/VPN_app && git add android/core/domain/ android/vpnservice/ && git commit -m "Писать логи ядра в буфер всегда, а в logcat — только в отладке"
```

---

### Task 9: TelemetryWorker — периодическая отправка метрик

**Files:**
- Modify: `gradle/libs.versions.toml` (добавить WorkManager)
- Modify: `app/build.gradle.kts` (зависимость)
- Create: `app/src/main/kotlin/com/impossi8le/vpnapp/telemetry/TelemetryWorker.kt`
- Create: `app/src/main/kotlin/com/impossi8le/vpnapp/telemetry/TelemetryScheduler.kt`
- Test: `app/src/test/kotlin/com/impossi8le/vpnapp/telemetry/TelemetrySchedulerTest.kt`

**Interfaces:**
- Consumes: `TelemetryApi.send`, `ReportQueue.peek`, `ReportQueue.remove`.
- Produces: `TelemetryScheduler.enqueue(context)` — ставит периодическую задачу.

- [ ] **Step 1: Добавить WorkManager в каталог**

В `gradle/libs.versions.toml`, секция `[versions]`:

```toml
workManager = "2.10.0"
```

В `[libraries]`:

```toml
work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "workManager" }
# work-testing нужен для проверки воркера без устройства.
work-testing = { module = "androidx.work:work-testing", version.ref = "workManager" }
```

- [ ] **Step 2: Написать падающий тест на конфигурацию расписания**

```kotlin
package com.impossi8le.vpnapp.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Параметры периодической отправки метрик.
 *
 * Проверяются как чистые значения, без WorkManager: сама задача запускается
 * системой, но её ОГРАНИЧЕНИЯ — это данные, и их видно тестом. Требовать
 * `UNMETERED` намеренно НЕ стали: метрики мелкие, а обновление вайфая у части
 * пользователей нет — с этим ограничением данные не пришли бы никогда.
 */
class TelemetrySchedulerTest {

    @Test
    fun `интервал — не меньше системного минимума`() {
        assertTrue(
            TelemetryScheduler.INTERVAL_MINUTES >= 15,
            "WorkManager не примет период меньше 15 минут — задача молча не запустится",
        )
    }

    @Test
    fun `сеть нужна любая, а не только wifi`() {
        assertEquals(NetworkRequirement.Connected, TelemetryScheduler.NETWORK_REQUIREMENT)
        assertFalse(
            TelemetryScheduler.NETWORK_REQUIREMENT == NetworkRequirement.Unmetered,
            "требование вайфая лишило бы данных пользователей мобильной сети",
        )
    }

    @Test
    fun `батарея не должна быть низкой`() {
        assertTrue(TelemetryScheduler.REQUIRES_BATTERY_NOT_LOW)
    }

    @Test
    fun `период описан в минутах и положителен`() {
        assertTrue(TelemetryScheduler.INTERVAL_MINUTES > 0)
        assertEquals(6, TimeUnit.HOURS.toMinutes(6) / 60)
    }
}

/** Требование к сети — отдельным перечислением, чтобы не тянуть `Constraints` в JVM-тест. */
enum class NetworkRequirement { Connected, Unmetered }
```

- [ ] **Step 3: Запустить тест и убедиться, что он падает**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:testDebugUnitTest --tests '*TelemetrySchedulerTest' --no-daemon
```

Ожидаемо: `Unresolved reference: TelemetryScheduler`.

- [ ] **Step 4: Написать планировщик**

`TelemetryScheduler.kt`:

```kotlin
package com.impossi8le.vpnapp.telemetry

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Постановка периодической отправки метрик.
 *
 * Ограничения выбраны так: любая сеть (метрики мелкие, а вайфай есть не у всех —
 * с `UNMETERED` данные не пришли бы вовсе), батарея не ниже порога, экспоненциальный
 * backoff при неудаче. Работает и когда приложение закрыто: задача переживает
 * перезапуск, иначе очередь копилась бы вечно.
 */
object TelemetryScheduler {

    const val INTERVAL_MINUTES = 6L * 60
    const val REQUIRES_BATTERY_NOT_LOW = true
    val NETWORK_REQUIREMENT = NetworkRequirement.Connected

    private const val UNIQUE_NAME = "com.impossi8le.vpnapp.telemetry"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(REQUIRES_BATTERY_NOT_LOW)
            .build()

        val request = PeriodicWorkRequestBuilder<TelemetryWorker>(
            INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        // KEEP, а не REPLACE: перезапуск приложения не должен сбрасывать таймер
        // и переносить отправку на новый полный интервал.
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
```

`TelemetryWorker.kt`:

```kotlin
package com.impossi8le.vpnapp.telemetry

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.impossi8le.vpnapp.core.network.TelemetryResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Фоновая отправка накопленных метрик.
 *
 * Разбор исхода — по [TelemetryResult]: принятое удаляем, «повторить» оставляем,
 * отвергнутое телом (`4xx`, кроме 401/403/429) выбрасываем — иначе негодный батч
 * душил бы очередь навсегда. При `401` очередь чистится: без токена отправить
 * нечего, а копить чужие события после выхода из аккаунта нельзя.
 */
class TelemetryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = TelemetryGraph.from(applicationContext)
        val queue = graph.queue
        val pending = queue.peek()
        if (pending.isEmpty()) return Result.success()

        val events = pending.mapNotNull { parse(it) }
        if (events.isEmpty()) {
            queue.remove(pending.mapNotNull(::eventIdOf))
            return Result.success()
        }

        return when (graph.api.send(appVersion = graph.appVersion(), events = events)) {
            TelemetryResult.Accepted -> {
                queue.remove(events.map { it.eventId })
                Result.success()
            }

            TelemetryResult.NotAuthorized -> {
                queue.clear()
                Result.success()
            }

            TelemetryResult.Rejected -> {
                queue.remove(events.map { it.eventId })
                Result.success()
            }

            TelemetryResult.Retry -> Result.retry()
        }
    }

    private fun parse(json: String): TelemetryEvent? =
        runCatching {
            Json.decodeFromString(TelemetryEvent.serializer(), json)
        }.getOrNull()

    private fun eventIdOf(json: String): String? =
        runCatching {
            Json.parseToJsonElement(json).jsonObject["event_id"]?.jsonPrimitive?.content
        }.getOrNull()
}
```

Поправки к коду выше, обязательные при реализации:

1. `queue.clear()` объявлен в `ReportQueue` (Task 4) и реализован в `FileReportQueue`
   (Task 5) — если к моменту этой задачи их правки ещё не влиты, добавить.
2. `TelemetryGraph.from(context)` — тонкая сборка (`ApiClient` + `FileReportQueue` +
   версия) в модуле `app`. Отдельного DI не заводим: две зависимости, граф `AppGraph`
   уже есть — переиспользовать его, если он доступен из воркера, иначе собрать локально.
3. `TelemetryResult` — `sealed interface`, все четыре исхода объявлены как
   `data object` без параметров (Task 6), поэтому `when` сравнивает объекты, а не
   биндит: `TelemetryResult.Accepted -> ...`. Ветка `else` не нужна — `when` над
   `sealed` исчерпывающий, и это ловит добавление нового исхода на компиляции.

- [ ] **Step 5: Запустить тест и убедиться, что он проходит**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:testDebugUnitTest --tests '*TelemetrySchedulerTest' --no-daemon
```

Ожидаемо: `BUILD SUCCESSFUL`, 4 теста.

- [ ] **Step 6: Поставить задачу при старте приложения**

В `MainActivity.onCreate` (или в `Application`-подобном месте графа) добавить:

```kotlin
        // Метрики уходят фоном, независимо от того, открыт ли экран.
        TelemetryScheduler.enqueue(applicationContext)
```

- [ ] **Step 7: Собрать приложение и прогнать тесты**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon
```

Ожидаемо: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Коммит**

```bash
cd /d/VPN_app && git add android/gradle/libs.versions.toml android/app/ && git commit -m "Отправлять метрики фоном на WorkManager под ограничениями сети и батареи"
```

---

### Task 10: Кнопка «Отправить отчёт» — собирает логи и конфиг

**Files:**
- Create: `feature/account/src/main/kotlin/com/impossi8le/vpnapp/feature/account/ReportCollector.kt`
- Modify: `app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt` (около строки 1146)
- Test: `feature/account/src/test/kotlin/com/impossi8le/vpnapp/feature/account/ReportCollectorTest.kt`

**Interfaces:**
- Consumes: `LogBuffer.snapshot`, `ConfigAnonymizer.anonymize`, `buildDiagnostics`, `ReportApi.send`.
- Produces: `ReportCollector.build(diagnosticsInput, logs, profileText): CollectedReport` с полями `text`, `logs`, `config`.

- [ ] **Step 1: Написать падающий тест**

```kotlin
package com.impossi8le.vpnapp.feature.account

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Сборка того, что уходит по кнопке.
 *
 * Отдельная функция, а не код внутри обработчика нажатия: границу приватности
 * нужно ДОКАЗАТЬ тестом. Здесь проверяется, что ключевой материал профиля в
 * собранный пакет не попадает, а причина сбоя из логов — попадает.
 */
class ReportCollectorTest {

    @Test
    fun `собранный отчёт не содержит ключей профиля`() {
        val profile = """
            client
            remote 192.0.2.10 443 udp
            <key>
            -----BEGIN PRIVATE KEY-----
            SUPERSECRETKEYDATA
            -----END PRIVATE KEY-----
            </key>
        """.trimIndent()

        val collected = ReportCollector.build(
            reportText = "Отчёт VPN-приложения",
            logs = listOf("TUN Error: tun_prop_error"),
            profileText = profile,
        )

        assertFalse(collected.config.contains("SUPERSECRETKEYDATA"), "ключ не должен уйти на сервер")
        assertTrue(collected.config.contains("remote 192.0.2.10 443 udp"), "структура нужна для разбора")
        assertTrue(collected.logs.contains("tun_prop_error"), "причина сбоя — главное в отчёте")
    }

    @Test
    fun `без профиля конфиг пустой, а не null-строка`() {
        val collected = ReportCollector.build("отчёт", listOf("лог"), profileText = null)

        assertTrue(collected.config.isEmpty(), "нет профиля — нет части в multipart")
    }

    @Test
    fun `текст отчёта проходит как есть`() {
        val text = "Отчёт VPN-приложения\nВерсия: 102 (102)"
        val collected = ReportCollector.build(text, emptyList(), null)

        assertTrue(collected.text.contains("Версия: 102"), "шаблон отчёта не переписываем")
    }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :feature:account:testDebugUnitTest --tests '*ReportCollectorTest' --no-daemon
```

Ожидаемо: `Unresolved reference: ReportCollector`.

- [ ] **Step 3: Написать реализацию**

```kotlin
package com.impossi8le.vpnapp.feature.account

import com.impossi8le.vpnapp.domain.diagnostics.ConfigAnonymizer
import com.impossi8le.vpnapp.domain.diagnostics.LogScrubber

/**
 * Пакет, который уходит на сервер по кнопке.
 *
 * [text] — существующий текстовый отчёт поддержке, [logs] — лог ядра, [config] —
 * обезличенный профиль. Пустая строка означает «части нет»: `null` в multipart
 * привёл бы к пустой части, а сервер прочитал бы её как «файл есть, но пустой».
 */
data class CollectedReport(
    val text: String,
    val logs: String,
    val config: String,
)

/**
 * Сборка отчёта по кнопке.
 *
 * Обезличивание конфига и скраб логов делаются ЗДЕСЬ, а не на сервере: приватный
 * ключ не должен покидать устройство даже в обезличенном виде, а серверу нельзя
 * доверять вырезание секретов из недоверенного входа.
 *
 * Чистая функция: тот же вход даёт тот же результат, поэтому проверяется на JVM.
 */
object ReportCollector {

    fun build(reportText: String, logs: List<String>, profileText: String?): CollectedReport =
        CollectedReport(
            text = reportText,
            logs = LogScrubber.scrubStream(logs).joinToString("\n"),
            config = profileText?.let(ConfigAnonymizer::anonymize).orEmpty(),
        )
}
```

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :feature:account:testDebugUnitTest --tests '*ReportCollectorTest' --no-daemon
```

Ожидаемо: `BUILD SUCCESSFUL`, 3 теста.

- [ ] **Step 5: Подключить в обработчике кнопки**

В `MainActivity.kt` (около строки 1146, в ветке `AppIntent.SendDiagnostics`)
заменить прямое копирование в буфер на отправку с падением в прежнее поведение:

```kotlin
                // Собираем пакет: текст отчёта + логи ядра + обезличенный профиль.
                // Ключи профиля вырезает ConfigAnonymizer — на устройстве, ДО
                // отправки: серверу нельзя доверять чистку недоверенного входа.
                val collected = ReportCollector.build(
                    reportText = report,
                    logs = logBuffer.snapshot(),
                    profileText = runCatching {
                        File(context.filesDir, "profile.ovpn").readText()
                    }.getOrNull(),
                )
                // Отправка не блокирует экран: пользователь не должен ждать сеть.
                lifecycleScope.launch {
                    when (val result = reportApi.send(
                        report = collected.text,
                        logs = collected.logs.ifEmpty { null },
                        config = collected.config.ifEmpty { null },
                        meta = metaJson,
                    )) {
                        is ReportResult.Sent -> toast("Отчёт отправлен")
                        ReportResult.NotAuthorized -> toast("Сессия истекла — войдите заново")
                        ReportResult.TooLarge -> toast("Отчёт слишком большой")
                        is ReportResult.Retry -> {
                            // Честно: не «отправлено», а как есть. Прежняя дорожка
                            // через буфер обмена остаётся запасным путём.
                            copyToClipboardAndNotify(context, report)
                        }
                    }
                }
```

`logBuffer` берётся из графа (тот же экземпляр, что пишет сервис — см. Task 8,
Step 5). Прежний код с `File(cacheDir, DIAGNOSTICS_FILE_NAME)` и `openSupportBot`
сохраняется в ветке неудачи — он никуда не девается.

- [ ] **Step 6: Собрать и прогнать тесты**

```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:assembleDebug :feature:account:testDebugUnitTest :app:testDebugUnitTest --no-daemon
```

Ожидаемо: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Коммит**

```bash
cd /d/VPN_app && git add android/feature/account/ android/app/ && git commit -m "Отправлять отчёт по кнопке: логи и обезличенный конфиг уходят на сервер"
```

---

### Task 11: Обновить документацию

**Files:**
- Modify: `docs/design/2026-10-06-implemented-ui-spec.md` (раздел диагностики)
- Modify: `docs/README.md` (карта документов)

- [ ] **Step 1: Обновить спеку интерфейса**

В разделе «Диагностический отчёт» (`implemented-ui-spec.md:525`) заменить описание
поведения: теперь отчёт не только копируется в буфер, но и **отправляется на
сервер**; при неудаче — прежнее поведение (буфер + бот). Добавить абзац:

```markdown
- Отправка: пакет (`text/plain` + логи + обезличенный `.ovpn`) уходит
  `POST /api/v1/report`. Ключевой материал профиля вырезается на устройстве
  (`ConfigAnonymizer`) — на сервер он не попадает никогда.
- При недоступной сети отчёт **не теряется**: сохраняется в локальной очереди,
  пользователю говорится «не отправлено, попробуем позже», и остаётся запасной
  путь — буфер обмена и бот поддержки.
- Ложное «отправлено» запрещено: это тот же класс ошибки, что ложный зелёный
  статус подключения.
```

- [ ] **Step 2: Обновить карту документации**

В `docs/README.md` в таблице «Вопрос → документ» добавить строку:

```markdown
| Какие эндпоинты шлют диагностику и что клиент ждёт в ответ | `architecture/2026-10-02-api-contract.md` §4.1–4.2 |
```

- [ ] **Step 3: Коммит**

```bash
cd /d/VPN_app && git add docs/ && git commit -m "Описать отправку отчётов и метрик в спеке интерфейса"
```

---

## Проверка перед завершением

- [ ] Все тесты проходят: `JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew test --no-daemon`
- [ ] Приложение собирается: `./gradlew :app:assembleDebug --no-daemon`
- [ ] Ни в одном тесте нет реального ключа из боевой фикстуры в теле запроса
- [ ] Буфер пишет логи в релизной сборке (проверяется тем, что `LogBuffer` не под `BuildConfig.DEBUG`)
