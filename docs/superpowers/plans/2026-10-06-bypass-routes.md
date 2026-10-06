# Обход РФ-сервисов: часть трафика мимо туннеля — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Трафик к перечисленным подсетям идёт напрямую, минуя туннель, так что пользователь не выключает VPN ради незаблокированных сервисов. Список тя­нется с нашего сервера.

**Architecture:** Список обходов — наши данные, применяем их в мосте сами, **не полагаясь на разбор директивы `route ... net_gateway` ядром** (её перевод в `tun_builder_exclude_route` не подтверждён, а наша реализация — пустышка). `VpnService.Builder.excludeRoute` (API 33+) — механизм. На API 26–32 обход недоступен, и тумблер честно выключен.

**Tech Stack:** Kotlin, OkHttp, kotlinx.serialization, Jetpack Compose, JUnit 5 + MockWebServer. Локальная сборка: `JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew … --no-daemon` из `android/`.

## Global Constraints

- `minSdk = 26`, `targetSdk = 35`. `Builder.excludeRoute` требует API 33 — на 26–32 обход недоступен, и это говорится пользователю словами, а не молча.
- `core:domain` — чистый Kotlin/JVM, **без kotlinx.serialization** (`core/domain/build.gradle.kts`). Разбор JSON живёт в `core:network`; в домен попадает готовый тип.
- Не заворачивать вывод Gradle в конвейер (ложная зелень).
- **Спайк (Task 0) — жёсткий гейт.** Пока не подтверждено, что `Builder.excludeRoute` реально уводит трафик мимо туннеля, задачи 1-5 не начинаются.
- `ProfileSanitizer` (`core/domain/.../tunnel/ProfileSanitizer.kt`) — образец чистой функции правки текста; если правка профиля понадобится, только по этому образцу.
- Русские строки из спеки — дословно.

---

### Task 0: СПАЙК — проверить `excludeRoute` на живом устройстве (ГЕЙТ)

**Files:**
- Modify (временно): `android/vpnservice/src/main/kotlin/com/impossi8le/vpnapp/vpnservice/VpnServiceTunBuilder.kt`
- Create: `docs/testing/2026-10-06-excluderoute-spike.md`

**Interfaces:**
- Consumes: `VpnServiceTunBuilder` (`:vpnservice/.../VpnServiceTunBuilder.kt`), `VpnTunnelService`.
- Produces: вердикт — работает ли механизм. Определяет, имеет ли смысл остальная часть плана.

- [ ] **Step 1: Временно добавить `excludeRoute` в мост**

В `VpnServiceTunBuilder.kt` добавить метод:

```kotlin
    /**
     * ВРЕМЕННО ДЛЯ СПАЙКА. Исключить подсеть из туннеля (API 33+).
     */
    fun excludeRouteForSpike(address: String, prefixLength: Int): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching {
                builder.excludeRoute(
                    android.net.IpPrefix(android.net.InetAddress.getByName(address), prefixLength),
                )
            }.isSuccess
        } else {
            false
        }
```

- [ ] **Step 2: Вызвать для тестовой подсети перед `establish()`**

В `VpnTunnelService.startTunnel` после `val tun = VpnServiceTunBuilder(this@VpnTunnelService, builder)` (строка 169) временно добавить:

```kotlin
        // ВРЕМЕННО ДЛЯ СПАЙКА: подсеть для проверки обхода. 1.1.1.1 — публичный
        // адрес, трассировка до него покажет, идёт ли он через туннель (tun0) или
        // напрямую (wlan0/rmnet).
        tun.excludeRouteForSpike("1.1.1.0", 24)
```

- [ ] **Step 3: Собрать, поставить, подключиться**

Run:
```bash
cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:assembleDebug --no-daemon
```
Установить (`scripts/install-apk.sh`), войти, подключить VPN, положить профиль в приватный каталог, если нужно.

- [ ] **Step 4: Проверить маршрутизацию обхода**

На подключённом VPN в `adb shell`:

```bash
adb shell "ip route get 1.1.1.1"
```

Expected (успех): маршрут уходит **не** через `tun0` (например `dev wlan0` или через шлюз локалки) — обход работает.
Expected (провал): маршрут через `tun0` — исключение не сработало.

Дополнительно сравнить с адресом внутри туннеля, например `adb shell "ip route get 8.8.8.8"` — он должен идти через `tun0`.

- [ ] **Step 5: Записать вердикт**

Create `docs/testing/2026-10-06-excluderoute-spike.md`:

```markdown
# Спайк excludeRoute: уводит ли API 33+ трафик мимо туннеля

Дата: 2026-10-06. Устройство/эмулятор: <модель, версия Android>.

## Команда и вывод

```
$ adb shell "ip route get 1.1.1.1"
<вставить дословный вывод>
$ adb shell "ip route get 8.8.8.8"
<вставить дословный вывод>
```

## Вердикт

<РАБОТАЕТ: 1.1.1.1 через wlan0/rmnet, 8.8.8.8 через tun0 — обход годится>
<НЕ РАБОТАЕТ: оба через tun0 — механизм не годится, фича пересматривается>
```

- [ ] **Step 6: Откатить временные правки спайка и закоммитить только документ**

Убрать `excludeRouteForSpike` и его вызов (они были временными). Оставить только документ:

```bash
git add docs/testing/2026-10-06-excluderoute-spike.md
git commit -m "Спайк: проверить, уводит ли excludeRoute трафик мимо туннеля"
```

**ГЕЙТ:** если вердикт «НЕ РАБОТАЕТ» — остановиться, вернуться к спеке `docs/superpowers/specs/2026-10-06-bypass-routes-design.md`, задачи 1-5 не выполнять.

---

### Task 1: Домен — тип обхода и разбор адресов

**Files:**
- Create: `android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/tunnel/BypassRoute.kt`
- Create: `android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/tunnel/BypassRouteTest.kt`

**Interfaces:**
- Consumes: ничего (чистый Kotlin).
- Produces:
  - `data class BypassRoute(val network: String, val prefixLength: Int)`
  - `fun parseBypassCidr(text: String): BypassRoute?` — `"87.240.129.0/24"` → `BypassRoute("87.240.129.0", 24); null` — мусор.
  - `fun maskToPrefixLength(mask: String): Int?` — `"255.255.255.0"` → `24; null` — не маска.
  - `fun parseBypassRouteLine(line: String): BypassRoute?` — формат владельца `"route 87.240.129.0 255.255.255.0 net_gateway"` → `BypassRoute("87.240.129.0", 24)`.

- [ ] **Step 1: Написать падающий тест**

Create `android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/tunnel/BypassRouteTest.kt`:

```kotlin
package com.impossi8le.vpnapp.domain.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Разбор адресов обхода. Два формата: CIDR (как отдаёт сервер) и маска (как
 * привычно в конфигах). Оба — в один тип, чтобы ниже был один механизм.
 */
class BypassRouteTest {

    @Test
    fun `cidr разбирается в сеть и префикс`() {
        assertEquals(BypassRoute("87.240.129.0", 24), parseBypassCidr("87.240.129.0/24"))
        assertEquals(BypassRoute("155.212.204.0", 24), parseBypassCidr("155.212.204.0/24"))
    }

    @Test
    fun `мусор в cidr даёт null, а не выдуманную сеть`() {
        assertNull(parseBypassCidr(""))
        assertNull(parseBypassCidr("87.240.129.0"))
        assertNull(parseBypassCidr("87.240.129.0/"))
        assertNull(parseBypassCidr("87.240.129.0/abc"))
        assertNull(parseBypassCidr("87.240.129.0/33"))
        assertNull(parseBypassCidr("не-адрес/24"))
    }

    @Test
    fun `маска переводится в длину префикса`() {
        assertEquals(24, maskToPrefixLength("255.255.255.0"))
        assertEquals(16, maskToPrefixLength("255.255.0.0"))
        assertEquals(32, maskToPrefixLength("255.255.255.255"))
        assertEquals(0, maskToPrefixLength("0.0.0.0"))
    }

    @Test
    fun `неровная маска — null, а не длина с потолка`() {
        // 255.255.255.128 — допустимая маска (/25), проверим отдельно
        assertEquals(25, maskToPrefixLength("255.255.255.128"))
        // Неровная маска (единицы вперемешку с нулями) не бывает длиной префикса
        assertNull(maskToPrefixLength("255.0.255.0"))
        assertNull(maskToPrefixLength("255.255.255"))
        assertNull(maskToPrefixLength("не-маска"))
    }

    @Test
    fun `строка route в формате владельца разбирается`() {
        assertEquals(
            BypassRoute("155.212.204.0", 24),
            parseBypassRouteLine("route 155.212.204.0 255.255.255.0 net_gateway"),
        )
        assertEquals(
            BypassRoute("87.240.129.0", 24),
            parseBypassRouteLine("route 87.240.129.0 255.255.255.0 net_gateway"),
        )
    }

    @Test
    fun `чужие строки и комментарии не разбираются`() {
        assertNull(parseBypassRouteLine("# vk.com"))
        assertNull(parseBypassRouteLine(""))
        assertNull(parseBypassRouteLine("route 10.0.0.0 255.0.0.0"))
        assertNull(parseBypassRouteLine("redirect-gateway def1"))
    }
}
```

- [ ] **Step 2: Прогнать — убедиться, что не компилируется**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests "com.impossi8le.vpnapp.domain.tunnel.BypassRouteTest" --no-daemon`
Expected: FAIL — `BypassRoute` не найдена.

- [ ] **Step 3: Реализовать**

Create `android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/tunnel/BypassRoute.kt`:

```kotlin
package com.impossi8le.vpnapp.domain.tunnel

/**
 * Подсеть, чей трафик идёт МИМО туннеля.
 *
 * Отдельный тип, а не пара строк: это значение, которое передаётся в мост и
 * проверяется тестом на JVM, без `VpnService`.
 */
data class BypassRoute(val network: String, val prefixLength: Int)

/** `"87.240.129.0/24"` → сеть и префикс; мусор → `null`. */
fun parseBypassCidr(text: String): BypassRoute? {
    val slash = text.indexOf('/')
    if (slash <= 0 || slash == text.lastIndex) return null
    val host = text.substring(0, slash)
    val prefix = text.substring(slash + 1).toIntOrNull() ?: return null
    if (prefix !in 0..32) return null
    if (!isIpv4(host)) return null
    return BypassRoute(host, prefix)
}

/**
 * Маска → длина префикса. `"255.255.255.0"` → `24`; неровная маска → `null`.
 *
 * Неровная маска (единицы вперемешку с нулями) длиной префикса не выражается;
 * вернуть для неё число значило бы выдумать неверную подсеть.
 */
fun maskToPrefixLength(mask: String): Int? {
    val octets = mask.split('.')
    if (octets.size != 4) return null
    var bits = 0
    var seenZero = false
    for (part in octets) {
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        for (bit in 7 downTo 0) {
            val set = (value shr bit) and 1 == 1
            if (set) {
                if (seenZero) return null // единица после нуля — маска неровная
                bits++
            } else {
                seenZero = true
            }
        }
    }
    return bits
}

/** Формат владельца: `"route 87.240.129.0 255.255.255.0 net_gateway"`. */
fun parseBypassRouteLine(line: String): BypassRoute? {
    val tokens = line.trim().split(Regex("\\s+"))
    if (tokens.size < 4) return null
    if (tokens[0] != "route") return null
    if (tokens[3] != "net_gateway") return null
    val host = tokens[1]
    if (!isIpv4(host)) return null
    val prefix = maskToPrefixLength(tokens[2]) ?: return null
    return BypassRoute(host, prefix)
}

private fun isIpv4(text: String): Boolean {
    val parts = text.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.length <= 3 &&
            part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
    }
}
```

- [ ] **Step 4: Прогнать — убедиться, что зелено**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests "com.impossi8le.vpnapp.domain.tunnel.BypassRouteTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/tunnel/BypassRoute.kt android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/tunnel/BypassRouteTest.kt
git commit -m "Добавить тип обхода и разбор адресов (CIDR и маска)"
```

---

### Task 2: Загрузка списка обходов с сервера

**Files:**
- Create: `android/core/network/src/main/kotlin/com/impossi8le/vpnapp/network/BypassRoutesApi.kt`
- Create: `android/core/network/src/test/kotlin/com/impossi8le/vpnapp/network/BypassRoutesApiTest.kt`
- Modify: `android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/tunnel/BypassRoutes.kt` (новый интерфейс сервиса — домен без JSON)

**Interfaces:**
- Consumes: `ApiClient`, `parseBypassCidr`.
- Produces:
  - `interface BypassRoutesService { suspend fun routes(): List<BypassRoute> }` (в `core:domain`).
  - `class BypassRoutesApi(client: ApiClient) : BypassRoutesService` (в `core:network`).

**Контракт сервера:**

```
GET {baseUrl}/app/bypass-routes?platform=android
  → 200 {"updated_at":"2026-10-06T00:00:00Z","routes":["87.240.129.0/24","155.212.204.0/24"]}
  → 404 / обрыв → пустой список (обходов нет; подключение не блокируется)
```

- [ ] **Step 1: Объявить интерфейс в домене**

Create `android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/tunnel/BypassRoutesService.kt`:

```kotlin
package com.impossi8le.vpnapp.domain.tunnel

/**
 * Источник списка обходов.
 *
 * Возвращает список, а не `Result`: неудача и пустой список означают одно —
 * обходов нет. Подключение от этого не блокируется, защита не ломается, и
 * различать «сервер молчит» и «обходов нет» вызывающему нечего.
 */
interface BypassRoutesService {
    suspend fun routes(): List<BypassRoute>
}
```

- [ ] **Step 2: Написать падающий тест**

Create `android/core/network/src/test/kotlin/com/impossi8le/vpnapp/network/BypassRoutesApiTest.kt`:

```kotlin
package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.tunnel.BypassRoute
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BypassRoutesApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BypassRoutesApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = BypassRoutesApi(ApiClient(baseUrl = server.url("/api/v1").toString().trimEnd('/')))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `список разбирается в подсети`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"updated_at":"2026-10-06T00:00:00Z","routes":["87.240.129.0/24","155.212.204.0/24"]}""",
            ),
        )

        assertEquals(
            listOf(BypassRoute("87.240.129.0", 24), BypassRoute("155.212.204.0", 24)),
            api.routes(),
        )
        assertEquals("/api/v1/app/bypass-routes?platform=android", server.takeRequest().path)
    }

    @Test
    fun `мусорные строки пропускаются, хорошие остаются`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"routes":["87.240.129.0/24","не-адрес","10.0.0.0/99"]}""",
            ),
        )

        assertEquals(listOf(BypassRoute("87.240.129.0", 24)), api.routes())
    }

    @Test
    fun `нет списка — обходов нет, а не падение`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody(""))
        assertTrue(api.routes().isEmpty())

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(api.routes().isEmpty())

        server.enqueue(MockResponse().setResponseCode(200).setBody("не json"))
        assertTrue(api.routes().isEmpty())
    }
}
```

- [ ] **Step 3: Прогнать — убедиться, что не компилируется**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:network:test --tests "com.impossi8le.vpnapp.network.BypassRoutesApiTest" --no-daemon`
Expected: FAIL — `BypassRoutesApi` не найдена.

- [ ] **Step 4: Реализовать**

Create `android/core/network/src/main/kotlin/com/impossi8le/vpnapp/network/BypassRoutesApi.kt`:

```kotlin
package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.tunnel.BypassRoute
import com.impossi8le.vpnapp.domain.tunnel.BypassRoutesService
import com.impossi8le.vpnapp.domain.tunnel.parseBypassCidr
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

/**
 * Список обходов с нашего сервера.
 *
 * Любая неудача — пустой список, а не исключение: отсутствие списка не должно
 * мешать ни подключению, ни защите. Мусорные адреса пропускаются поштучно —
 * одна битая строка не отменяет рабочие.
 */
class BypassRoutesApi(
    private val client: ApiClient,
    private val platform: String = "android",
    private val json: Json = Json { ignoreUnknownKeys = true },
) : BypassRoutesService {

    override suspend fun routes(): List<BypassRoute> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${client.baseUrl}/app/bypass-routes?platform=$platform")
                .get()
                .build()
            client.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                parse(response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parse(body: String): List<BypassRoute> =
        json.parseToJsonElement(body).jsonObject["routes"]?.jsonArray.orEmpty()
            .mapNotNull { it.toString().trim('"').let(::parseBypassCidr) }
}
```

- [ ] **Step 5: Прогнать — убедиться, что зелено**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:network:test --tests "com.impossi8le.vpnapp.network.BypassRoutesApiTest" --no-daemon`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/tunnel/BypassRoutesService.kt android/core/network/src/main/kotlin/com/impossi8le/vpnapp/network/BypassRoutesApi.kt android/core/network/src/test/kotlin/com/impossi8le/vpnapp/network/BypassRoutesApiTest.kt
git commit -m "Тянуть список обходов РФ-сервисов с сервера"
```

---

### Task 3: Применить обходы в мосте

**Files:**
- Modify: `android/vpnengine/src/main/kotlin/com/impossi8le/vpnapp/vpnengine/TunBridge.kt` (добавить метод)
- Modify: `android/vpnservice/src/main/kotlin/com/impossi8le/vpnapp/vpnservice/VpnServiceTunBuilder.kt` (реализация)
- Modify: `android/vpnservice/src/main/kotlin/com/impossi8le/vpnapp/vpnservice/VpnTunnelService.kt` (вызвать до `establish()`)

**Interfaces:**
- Consumes: `BypassRoute` (Task 1), `SystemState` в сервисе.
- Produces: `TunBridge.excludeRoute(address: String, prefixLength: Int, ipv6: Boolean): Boolean`.

- [ ] **Step 1: Добавить метод в интерфейс `TunBridge`**

В `TunBridge.kt` после `addRoute` (строка 42) добавить:

```kotlin
    /**
     * Исключить подсеть из туннеля: её трафик пойдёт напрямую.
     *
     * `false` — исключить не удалось (например, платформа не поддерживает).
     * Возвращать `true` при неумении нельзя: вызывающий решил бы, что трафик
     * уходит мимо, а он пошёл бы в туннель — молчаливая ложь о маршрутизации.
     */
    fun excludeRoute(address: String, prefixLength: Int, ipv6: Boolean): Boolean
```

- [ ] **Step 2: Реализовать в `VpnServiceTunBuilder`**

В `VpnServiceTunBuilder.kt` после `addRoute` (строка 59) добавить:

```kotlin
    /**
     * Исключение подсети из туннеля. `VpnService.Builder.excludeRoute` появился
     * только в API 33; ниже возвращаем `false` честно — обход там недоступен, и
     * вызывающий обязан это учесть, а не притвориться, что обход работает.
     */
    override fun excludeRoute(address: String, prefixLength: Int, ipv6: Boolean): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return false
        return runCatching {
            builder.excludeRoute(
                android.net.IpPrefix(android.net.InetAddress.getByName(address), prefixLength),
            )
        }.isSuccess
    }
```

- [ ] **Step 3: Применить список в сервисе до `establish()`**

В `VpnTunnelService.kt`: сервис должен получить список обходов. Читаем его тем же способом, что и профиль — из файла в приватном каталоге (путь приходит в extras, содержимое — в файле; extras для секретов не годятся, но список не секретен, однако файл надёжнее при перезапуске сервиса системой).

Добавить константу в `companion object`:

```kotlin
        /** Путь к файлу со списком обходов (по строке `network/prefix`). */
        const val EXTRA_BYPASS_FILE = "bypass_file"
```

В `startTunnel`, перед строкой `val newSession = OpenVpn3Session(tun)`, добавить:

```kotlin
        // Обходы применяются ДО establish(): маршрут добавляется к уже
        // существующему списку исключений интерфейса.
        applyBypassRoutes(tun, bypassFilePath)
```

При этом `startTunnel` получает второй параметр `bypassFilePath: String?`; в `onStartCommand` он читается из `intent?.getStringExtra(EXTRA_BYPASS_FILE)`. Реализация:

```kotlin
    /**
     * Исключить из туннеля подсети обхода.
     *
     * Файла нет или строки битые — просто меньше исключений: подключение и
     * защита от этого не страдают. Неудача исключения (API < 33) сообщается
     * экрану отдельно — здесь только факт.
     */
    private fun applyBypassRoutes(tun: VpnServiceTunBuilder, path: String?) {
        if (path == null) return
        val lines = runCatching { File(path).readLines() }.getOrDefault(emptyList())
        for (line in lines) {
            val route = parseBypassRouteLine("route ${line.trim().replace('/', ' ')} net_gateway")
                ?: line.trim().let { cidr ->
                    val slash = cidr.indexOf('/')
                    if (slash > 0) parseBypassCidr(cidr) else null
                }
                ?: continue
            tun.excludeRoute(route.network, route.prefixLength, ipv6 = false)
        }
    }
```

**Упрощение при реализации:** формат файла фиксируем как одну CIDR-строку на строку (`87.240.129.0/24`). Тогда `applyBypassRoutes` читает `parseBypassCidr(line)` напрямую, без развилки с маской. Запись файла — на стороне приложения (Task 4).

Добавить импорты: `com.impossi8le.vpnapp.domain.tunnel.parseBypassCidr`, `com.impossi8le.vpnapp.domain.tunnel.BypassRoute`.

- [ ] **Step 4: Собрать модуль**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :vpnservice:build --no-daemon`
Expected: BUILD SUCCESSFUL. (Проверить, что `:vpnservice` видит `core:domain` — он его уже подключает, `vpnservice/build.gradle.kts:37`.)

- [ ] **Step 5: Commit**

```bash
git add android/vpnengine/src/main/kotlin/com/impossi8le/vpnapp/vpnengine/TunBridge.kt android/vpnservice/src/main/kotlin/com/impossi8le/vpnapp/vpnservice/VpnServiceTunBuilder.kt android/vpnservice/src/main/kotlin/com/impossi8le/vpnapp/vpnservice/VpnTunnelService.kt
git commit -m "Применять обходы в мосте через excludeRoute (API 33+)"
```

---

### Task 4: Загрузить список, записать файл, показать честный статус

**Files:**
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/AppGraph.kt` (добавить `bypassRoutes`)
- Modify: `android/core/tunnel/src/main/kotlin/com/impossi8le/vpnapp/tunnel/AppTunnelController.kt` (передать путь к файлу обхода при `connect`)
- Modify: `android/feature/home/src/main/kotlin/com/impossi8le/vpnapp/feature/home/StatusPresentation.kt` (строка о частичном обходе)

**Interfaces:**
- Consumes: `BypassRoutesApi` (Task 2), `AppTunnelController.connect`, `StatusPresentation(runningConfigName)`.
- Produces: `StatusPresentation.detail` при активных обходах; файл обхода в `filesDir`.

- [ ] **Step 1: Написать тест презентации — обход меняет подробность**

Дополнить `android/feature/home/src/test/kotlin/com/impossi8le/vpnapp/feature/home/StatusPresentationTest.kt`:

```kotlin
    @Test
    fun `при активных обходах подробность говорит, что часть трафика идёт напрямую`() {
        // «Подключено» при обходах означает «не всё через туннель» — молчать об
        // этом значит повторять ту ложную уверенность, против которой §6.
        val p = ConnectionStatus.VerifyingProtection.presentation(
            runningConfigName = "Нидерланды",
            bypassCount = 6,
        )

        assertEquals("Подключено", p.title)
        assertTrue(
            p.detail.contains("напрямую") || p.detail.contains("обход"),
            "подробность должна сказать о частичном обходе, было: ${p.detail}",
        )
    }

    @Test
    fun `без обходов подробность как прежде`() {
        val p = ConnectionStatus.VerifyingProtection.presentation("Нидерланды", bypassCount = 0)
        assertEquals("Нидерланды", p.detail)
    }
```

- [ ] **Step 2: Прогнать — убедиться, что не компилируется**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :feature:home:test --tests "com.impossi8le.vpnapp.feature.home.StatusPresentationTest" --no-daemon`
Expected: FAIL — у `presentation` нет параметра `bypassCount`.

- [ ] **Step 3: Добавить параметр в `presentation`**

В `StatusPresentation.kt` изменить сигнатуру `fun ConnectionStatus.presentation(runningConfigName: String? = null)` на:

```kotlin
fun ConnectionStatus.presentation(
    runningConfigName: String? = null,
    bypassCount: Int = 0,
): StatusPresentation
```

И в ветке трёх подключённых состояний заменить `detail = runningConfigName ?: "Соединение установлено"` на:

```kotlin
        detail = if (bypassCount > 0) {
            // Обходы означают, что часть трафика идёт мимо туннеля. Молчать об
            // этом нельзя: «Подключено» иначе читается как «защищено всё».
            "Подключено, часть трафика идёт напрямую: сервисов — $bypassCount"
        } else {
            runningConfigName ?: "Соединение установлено"
        },
```

- [ ] **Step 4: Прогнать — зелено**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :feature:home:test --tests "com.impossi8le.vpnapp.feature.home.StatusPresentationTest" --no-daemon`
Expected: PASS.

- [ ] **Step 5: Собрать граф и записывать файл обхода при подключении**

В `AppGraph.kt` добавить:

```kotlin
    val bypassRoutes: BypassRoutesService = BypassRoutesApi(apiClient)
```

В `MainActivity` перед `connect()` — загрузить список, записать в `filesDir/bypass-routes.txt` (по строке `network/prefix`), передать путь сервису через `EXTRA_BYPASS_FILE` (в `AppTunnelController.connect`). Число строк держать в состоянии приложения (`bypassCount`) и передавать в `presentation`.

**Проверить фактическую сигнатуру** `AppTunnelController.connect` (`core/tunnel/.../AppTunnelController.kt`) и добавить параметр `bypassFilePath: String?` по образцу существующего `profilePath`.

- [ ] **Step 6: Собрать приложение и прогнать все тесты**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew test :app:assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL, все тесты зелёные.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/com/impossi8le/vpnapp/AppGraph.kt android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt android/core/tunnel/src/main/kotlin/com/impossi8le/vpnapp/tunnel/AppTunnelController.kt android/feature/home/src/main/kotlin/com/impossi8le/vpnapp/feature/home/StatusPresentation.kt android/feature/home/src/test/kotlin/com/impossi8le/vpnapp/feature/home/StatusPresentationTest.kt android/vpnservice/src/main/kotlin/com/impossi8le/vpnapp/vpnservice/VpnTunnelService.kt
git commit -m "Тянуть обходы при подключении и честно говорить о частичном обходе"
```

---

### Task 5: Проверка на устройстве и документы

**Files:**
- Create: `docs/testing/2026-10-06-bypass-routes-phone.md`
- Modify: `android/README.md`

**Interfaces:**
- Consumes: собранный APK, сервер со списком обходов.
- Produces: документ-свидетельство.

- [ ] **Step 1: Проверить на устройстве (Android 13+)**

Подключить VPN, затем:

```bash
adb shell "ip route get <адрес из обхода>"
adb shell "ip route get <адрес, НЕ входящий в обход>"
```

Expected: первый — не через `tun0`; второй — через `tun0`.

- [ ] **Step 2: Проверить на Android < 13 (если есть устройство/эмулятор)**

Expected: обход не применяется; экран честно говорит, что обход недоступен на этой версии (текст добавить в Task 4 при реализации тумблера, если он есть). Если тумблера нет — обходы просто не применяются, и это должно быть записано как известное ограничение.

- [ ] **Step 3: Записать свидетельство**

Create `docs/testing/2026-10-06-bypass-routes-phone.md` с таблицей: адрес → интерфейс (`tun0` или прямой) → вердикт; отдельный раздел «не проверено» (API < 33, поведение при недоступном сервере списка).

- [ ] **Step 4: Обновить README**

В `android/README.md` в таблицу состояния добавить строку про обходы с честным ограничением (API 33+).

- [ ] **Step 5: Commit**

```bash
git add docs/testing/2026-10-06-bypass-routes-phone.md android/README.md
git commit -m "Записать проверку обходов на телефоне"
```

---

## Self-Review

**Покрытие спеки:**
- «Обход РФ-сервисов, не выключая VPN» → Tasks 1-4. ✓
- «Список тянется с сервера» → Task 2 (интерфейс+API), Task 4 (загрузка при подключении). ✓
- «Формат `route … net_gateway`» → Task 1 (`parseBypassRouteLine`), но применяется НЕ через профиль, а через мост (Task 3) — как требует спека. ✓
- «Ограничение API 33» → Task 3 Step 2 (`false` ниже 33), Task 5 Step 2. ✓
- «Честность статуса при обходах» → Task 4 Steps 1-3. ✓
- «Спайк — гейт» → Task 0. ✓

**Осознанно НЕ входит:** пользовательское добавление обходов (отдельное решение из спеки), поднятие `minSdk` до 33 (решение владельца), ведение списка на сервере (Task 2 контракт + ответственность владельца).

**Согласованность типов:** `BypassRoute(network, prefixLength)` — Task 1; используется в Task 2 (`BypassRoutesService.routes(): List<BypassRoute>`) и Task 3 (`excludeRoute(route.network, route.prefixLength, …)`). `TunBridge.excludeRoute(address, prefixLength, ipv6)` — Task 3, объявлен и реализован в одной задаче. `presentation(runningConfigName, bypassCount)` — Task 4, сигнатура и вызов согласованы. ✓

**Плейсхолдеры:** Task 3 Step 3 содержит упрощение в тексте (зафиксировать формат файла как CIDR) — это уточнение, а не отложенная работа; код дан. В Task 5 `<адрес из обхода>` — значение, зависящее от фактического списка с сервера. Допустимо.

**Известная слабость:** Task 4 Step 5 описывает интеграцию в `MainActivity` прозой, а не полным кодом, потому что точная сигнатура `AppTunnelController.connect` и место вызова во ViewModel требуют сверки на месте. Исполнителю надлежит прочитать `AppTunnelController.kt` и `HomeViewModel.kt` и добавить параметр по образцу `profilePath`. Это единственное место плана без дословного кода — отмечено честно, а не спрятано.
