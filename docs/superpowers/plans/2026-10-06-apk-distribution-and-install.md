# Раздача APK с нашего сервера, понятный путь установки, Play Protect — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Источник сведений о новой версии переключается с GitHub на наш сервер (с GitHub как запасным), в приложении появляется понятная точка входа на страницу загрузки, а каналы раздачи и Play Protect зафиксированы решением владельца.

**Architecture:** Граница уже есть: интерфейс домена `UpdateService` изолирует источник, `UpdateApi` (GitHub) — одна реализация. Добавляем `ServerUpdateApi` и `FallbackUpdateService` (наш сервер первый, GitHub — запасной), переключаем одну строку в `AppGraph`. Механизм скачивания и установки не трогаем вовсе — URL APK уже приходит данными.

**Tech Stack:** Kotlin, OkHttp, kotlinx.serialization, MockWebServer (JUnit 5). Локальная сборка: `JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew … --no-daemon` из `android/`.

## Global Constraints

- `minSdk = 26`, `targetSdk = 35` — не менять.
- Не заворачивать вывод Gradle в конвейер (`| tee` маскирует код возврата → ложная зелень).
- Механизм обновления (`ApkDownloader`, `ApkInstaller`, `UpdateVerdictStore`, порог версий) **не менять** — задача только про источник сведений.
- Домен под раздачу — решение владельца. В коде используем существующий хост `194-87-252-181.sslip.io` (работает сегодня); смена домена — правка одной константы.
- Интерфейс `UpdateService` (`core/domain/.../update/UpdateService.kt:30`) менять нельзя — от него зависят потребители.
- Русские строки из спеки — дословно.

---

### Task 1: `ServerUpdateApi` — источник версий с нашего сервера

**Files:**
- Create: `android/core/network/src/main/kotlin/com/impossi8le/vpnapp/network/ServerUpdateApi.kt`
- Create: `android/core/network/src/test/kotlin/com/impossi8le/vpnapp/network/ServerUpdateApiTest.kt`

**Interfaces:**
- Consumes: `UpdateService` (`core/domain/.../update/UpdateService.kt:30`), `ReleaseInfo(tagName, apkUrl)` (`:9`), `UpdateError`/`UpdateException` (`:12`/`:26`), `parseMinSupported(String): Int?` (`core/domain/.../update/UpdateStatus.kt:53`), `ApiClient`.
- Produces: `class ServerUpdateApi(client: ApiClient, platform: String = "android") : UpdateService` — реализует `latestRelease(): Result<ReleaseInfo>` и `minSupported(): Int?`. `tagName` синтезируется как `"android-v<version>"`, чтобы нижележащий `parseReleaseTag` работал без изменений.

**Контракт сервера (согласовать с `docs/api/` до реализации):**

```
GET {baseUrl}/app/latest?platform=android
  → 200 {"version": 95, "apk_url": "https://<домен>/app/vpn-95.apk"}
  → 404 {"error":{"code":"release_not_found"}}   — релизов нет
GET {baseUrl}/app/min-supported
  → 200 "90"        (просто число, как прежний min-supported.txt)
```

- [ ] **Step 1: Написать падающий тест**

Create `android/core/network/src/test/kotlin/com/impossi8le/vpnapp/network/ServerUpdateApiTest.kt`:

```kotlin
package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.update.UpdateError
import com.impossi8le.vpnapp.domain.update.UpdateException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Источник версий — наш сервер, а не GitHub.
 *
 * Тег синтезируется как `android-v<номер>`: разбор тега остаётся правилом
 * домена (`parseReleaseTag`), и серверный ответ не должен вводить второй формат.
 */
class ServerUpdateApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ServerUpdateApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ServerUpdateApi(ApiClient(baseUrl = server.url("/api/v1").toString().trimEnd('/')))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `версия и ссылка на apk разбираются`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"version":95,"apk_url":"https://example.com/vpn-95.apk"}""",
            ),
        )

        val info = api.latestRelease().getOrThrow()
        assertEquals("android-v95", info.tagName)
        assertEquals("https://example.com/vpn-95.apk", info.apkUrl)

        val request = server.takeRequest()
        assertEquals("/api/v1/app/latest?platform=android", request.path)
    }

    @Test
    fun `ответ без ссылки на apk — неполный ответ, а не обновление`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":95}"""))

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        assertTrue(error is UpdateError.Unexpected, "ссылки нет => предлагать нечего, было $error")
    }

    @Test
    fun `нет релизов — это не ошибка связи`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"release_not_found"}}"""))

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        assertEquals(UpdateError.NotFound, error)
    }

    @Test
    fun `обрыв связи — сбой сети`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        assertEquals(UpdateError.NetworkUnavailable, error)
    }

    @Test
    fun `порог читается числом, мусор даёт null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("90"))
        assertEquals(90, api.minSupported())

        server.enqueue(MockResponse().setResponseCode(200).setBody("не число"))
        assertNull(api.minSupported())

        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        assertNull(api.minSupported())
    }
}
```

- [ ] **Step 2: Прогнать — убедиться, что не компилируется**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:network:test --tests "com.impossi8le.vpnapp.network.ServerUpdateApiTest" --no-daemon`
Expected: FAIL — `ServerUpdateApi` не найдена (Unresolved reference).

- [ ] **Step 3: Реализовать `ServerUpdateApi`**

Create `android/core/network/src/main/kotlin/com/impossi8le/vpnapp/network/ServerUpdateApi.kt`:

```kotlin
package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.update.ReleaseInfo
import com.impossi8le.vpnapp.domain.update.UpdateError
import com.impossi8le.vpnapp.domain.update.UpdateException
import com.impossi8le.vpnapp.domain.update.UpdateService
import com.impossi8le.vpnapp.domain.update.parseMinSupported
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

/**
 * Источник версий — наш сервер, а не GitHub.
 *
 * Причина: `api.github.com` в РФ бывает недоступен, а раздача обновлений не
 * должна зависеть от чужого домена. Сервер отдаёт номер версии и ссылку на APK;
 * ссылка ведёт на ту же раздачу.
 *
 * Тег синтезируется как `android-v<номер>`, а не приходит готовым: формат тега —
 * правило домена (`parseReleaseTag`), и серверный ответ не должен заводить
 * второй формат сборки.
 */
class ServerUpdateApi(
    private val client: ApiClient,
    private val platform: String = "android",
    private val json: Json = Json { ignoreUnknownKeys = true },
) : UpdateService {

    override suspend fun minSupported(): Int? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("${client.baseUrl}/app/min-supported").get().build()
            client.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                parseMinSupported(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            // Нет порога — блокировать нечем. См. isVersionSupported.
            null
        }
    }

    override suspend fun latestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${client.baseUrl}/app/latest?platform=$platform")
                .get()
                .build()

            client.http.newCall(request).execute().use { response ->
                when {
                    // «Релизов нет» и «проверить не удалось» — разные вещи: первое
                    // значит «обновляться не с чего», второе требует повтора.
                    response.code == 404 -> Result.failure(UpdateException(UpdateError.NotFound))
                    !response.isSuccessful -> Result.failure(
                        UpdateException(UpdateError.Unexpected(response.code)),
                    )
                    else -> Result.success(parseLatest(response.body?.string().orEmpty()))
                }
            }
        } catch (e: UpdateException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(UpdateException(UpdateError.NetworkUnavailable))
        } catch (e: Exception) {
            Result.failure(UpdateException(UpdateError.Unexpected(statusCode = 0)))
        }
    }

    private fun parseLatest(body: String): ReleaseInfo {
        val root = json.parseToJsonElement(body).jsonObject
        val version = root["version"]?.toString()?.trim('"')?.toIntOrNull()
        val apkUrl = root["apk_url"]?.toString()?.trim('"')
        if (version == null || apkUrl.isNullOrEmpty()) {
            // Обновление без ссылки предложить нельзя: кнопка «Скачать» молчала бы.
            throw UpdateException(UpdateError.Unexpected(statusCode = 200))
        }
        return ReleaseInfo(tagName = "android-v$version", apkUrl = apkUrl)
    }
}
```

**Проверить перед запуском:** есть ли у `ApiClient` публичное свойство `baseUrl`. Если нет — добавить его (`val baseUrl: String` к конструктору, параметр уже принимается, см. `ApiClient(baseUrl = API_BASE_URL)` в `AppGraph.kt:38`) либо использовать уже существующий способ получить адрес. Сверить фактический вид `ApiClient` (`core/network/.../ApiClient.kt`).

- [ ] **Step 4: Прогнать — убедиться, что зелено**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:network:test --tests "com.impossi8le.vpnapp.network.ServerUpdateApiTest" --no-daemon`
Expected: PASS (5 тестов).

- [ ] **Step 5: Commit**

```bash
git add android/core/network/src/main/kotlin/com/impossi8le/vpnapp/network/ServerUpdateApi.kt android/core/network/src/test/kotlin/com/impossi8le/vpnapp/network/ServerUpdateApiTest.kt android/core/network/src/main/kotlin/com/impossi8le/vpnapp/network/ApiClient.kt
git commit -m "Добавить источник версий с нашего сервера"
```

---

### Task 2: `FallbackUpdateService` — сервер первый, GitHub запасной

**Files:**
- Create: `android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/update/FallbackUpdateService.kt`
- Create: `android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/update/FallbackUpdateServiceTest.kt`
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/AppGraph.kt:46`

**Interfaces:**
- Consumes: `UpdateService` (`:30`), `ReleaseInfo`, `UpdateException`.
- Produces: `class FallbackUpdateService(primary: UpdateService, fallback: UpdateService) : UpdateService`.

- [ ] **Step 1: Написать падающий тест**

Create `android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/update/FallbackUpdateServiceTest.kt`:

```kotlin
package com.impossi8le.vpnapp.domain.update

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Наш сервер первый, GitHub — только если сервер не ответил.
 *
 * Так переход на свой хостинг не роняет раздачу в день выката: пока серверные
 * эндпоинты не готовы, обновления продолжают приходить с GitHub, а как только
 * сервер заговорит — используется он.
 */
class FallbackUpdateServiceTest {

    private fun service(release: Result<ReleaseInfo>, min: Int?) = object : UpdateService {
        override suspend fun latestRelease(): Result<ReleaseInfo> = release
        override suspend fun minSupported(): Int? = min
    }

    @Test
    fun `успех основного источника — запасной не трогается`() = runTest {
        val primary = service(Result.success(ReleaseInfo("android-v95", "https://srv/95.apk")), 90)
        var fallbackCalled = false
        val fallback = object : UpdateService {
            override suspend fun latestRelease(): Result<ReleaseInfo> {
                fallbackCalled = true
                return Result.success(ReleaseInfo("android-v1", "https://gh/1.apk"))
            }
            override suspend fun minSupported(): Int? = 1
        }

        val info = FallbackUpdateService(primary, fallback).latestRelease().getOrThrow()

        assertEquals("https://srv/95.apk", info.apkUrl)
        assertTrue(!fallbackCalled, "запасной источник не должен вызываться при успехе основного")
    }

    @Test
    fun `отказ основного — берём запасной`() = runTest {
        val primary = service(
            Result.failure(UpdateException(UpdateError.NetworkUnavailable)),
            null,
        )
        val fallback = service(Result.success(ReleaseInfo("android-v94", "https://gh/94.apk")), null)

        val info = FallbackUpdateService(primary, fallback).latestRelease().getOrThrow()

        assertEquals("https://gh/94.apk", info.apkUrl)
    }

    @Test
    fun `оба отказали — отказ`() = runTest {
        val primary = service(Result.failure(UpdateException(UpdateError.NetworkUnavailable)), null)
        val fallback = service(Result.failure(UpdateException(UpdateError.NetworkUnavailable)), null)

        val result = FallbackUpdateService(primary, fallback).latestRelease()

        assertTrue(result.isFailure)
    }

    @Test
    fun `порог берётся с сервера, а при его отсутствии — с запасного`() = runTest {
        assertEquals(90, FallbackUpdateService(service(Result.success(ReleaseInfo("android-v95", "u")), 90), service(Result.success(ReleaseInfo("android-v94", "u")), 80)).minSupported())
        assertEquals(80, FallbackUpdateService(service(Result.success(ReleaseInfo("android-v95", "u")), null), service(Result.success(ReleaseInfo("android-v94", "u")), 80)).minSupported())
    }
}
```

- [ ] **Step 2: Прогнать — убедиться, что не компилируется**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests "com.impossi8le.vpnapp.domain.update.FallbackUpdateServiceTest" --no-daemon`
Expected: FAIL — `FallbackUpdateService` не найдена.

- [ ] **Step 3: Реализовать**

Create `android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/update/FallbackUpdateService.kt`:

```kotlin
package com.impossi8le.vpnapp.domain.update

/**
 * Основной источник, а при его отказе — запасной.
 *
 * Переход на свой хостинг не должен ронять раздачу в день выката: пока серверные
 * эндпоинты не отвечают, обновления приходят с прежнего места.
 *
 * Оговорка про порог: [UpdateService.minSupported] отдаёт `null` и когда порога
 * нет, и когда узнать не удалось. Поэтому при `null` от основного спрашиваем
 * запасной — даже если порога у основного честно нет. Разница безвредна: пороги
 * у обоих источников обязаны совпадать, а лишний порог безвреднее пропущенного.
 */
class FallbackUpdateService(
    private val primary: UpdateService,
    private val fallback: UpdateService,
) : UpdateService {

    override suspend fun latestRelease(): Result<ReleaseInfo> =
        primary.latestRelease().recoverCatching { fallback.latestRelease().getOrThrow() }

    override suspend fun minSupported(): Int? =
        primary.minSupported() ?: fallback.minSupported()
}
```

- [ ] **Step 4: Прогнать — убедиться, что зелено**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests "com.impossi8le.vpnapp.domain.update.FallbackUpdateServiceTest" --no-daemon`
Expected: PASS (4 теста).

- [ ] **Step 5: Переключить точку сборки**

В `AppGraph.kt` заменить строку 46 `val updateApi = UpdateApi(apiClient)` и её доккомментарий на:

```kotlin
    /**
     * Источник сведений о новой версии: наш сервер, а GitHub — запасной.
     *
     * Сервер первый, потому что `api.github.com` в РФ бывает недоступен, а
     * обновление не должно зависеть от чужого домена. GitHub оставлен до тех
     * пор, пока серверные эндпоинты не проверены на устройстве (`docs/api/`).
     */
    val updateApi: UpdateService = FallbackUpdateService(
        primary = ServerUpdateApi(apiClient),
        fallback = UpdateApi(apiClient),
    )
```

Добавить импорты: `com.impossi8le.vpnapp.domain.update.UpdateService`, `com.impossi8le.vpnapp.network.ServerUpdateApi`.

- [ ] **Step 6: Собрать приложение и прогнать все тесты**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew test :app:assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL, все тесты зелёные.

- [ ] **Step 7: Commit**

```bash
git add android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/update/FallbackUpdateService.kt android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/update/FallbackUpdateServiceTest.kt android/app/src/main/kotlin/com/impossi8le/vpnapp/AppGraph.kt
git commit -m "Переключить источник обновлений на свой сервер с запасом GitHub"
```

---

### Task 3: Точка входа на страницу загрузки в приложении

**Files:**
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt:85` (константы), рядом с `openSupportBot` (строка 114)
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/AppRoot.kt:442` (новый интент), `:277` (маппинг кнопки)
- Modify: `MainActivity.kt` редьюсер — новый интент

**Interfaces:**
- Consumes: `AppIntent.OpenSupportChat` (образец), `openSupportBot(context)` (образец).
- Produces: `AppIntent.OpenDownloadPage`; `private fun openDownloadPage(context: Context)`.

- [ ] **Step 1: Добавить константу адреса страницы загрузки**

В `MainActivity.kt` рядом с `SUPPORT_BOT_URL` (строка 85) добавить:

```kotlin
/**
 * Страница загрузки приложения — человекочитаемый адрес, а не прямая ссылка на
 * файл. Пользователь должен видеть, что качает и какой версии, а не получать
 * APK из ссылки, которая выглядит как фишинг.
 *
 * Домен согласует владелец (см. `docs/superpowers/specs/2026-10-06-apk-distribution-and-install-design.md`);
 * пока используется существующий хост сервиса.
 */
private const val DOWNLOAD_PAGE_URL = "https://194-87-252-181.sslip.io/app"
```

- [ ] **Step 2: Добавить функцию открытия страницы**

После `openSupportBot` (строка 121) добавить:

```kotlin
/** Открыть страницу загрузки в браузере. Браузера может не быть — не роняем. */
private fun openDownloadPage(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(DOWNLOAD_PAGE_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
```

- [ ] **Step 3: Объявить интент**

В `AppRoot.kt` рядом с `OpenSupportChat` (в `sealed interface AppIntent`, строка 442) добавить:

```kotlin
    /** Открыть страницу загрузки: там версия, APK и как пройти Play Protect. */
    data object OpenDownloadPage : AppIntent
```

- [ ] **Step 4: Направить кнопку «Как обновить ›» на страницу загрузки**

В `AppRoot.kt:277` заменить `onHowToUpdate = { onIntent(AppIntent.OpenSupportChat) }` на:

```kotlin
                onHowToUpdate = { onIntent(AppIntent.OpenDownloadPage) },
```

- [ ] **Step 5: Обработать интент в редьюсере**

В `MainActivity.kt`, рядом с `AppIntent.OpenSupportChat -> openSupportBot(context)` (строка 777), добавить:

```kotlin
                AppIntent.OpenDownloadPage -> openDownloadPage(context)
```

- [ ] **Step 6: Собрать и прогнать тесты**

Run: `cd android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew test :app:assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt android/app/src/main/kotlin/com/impossi8le/vpnapp/AppRoot.kt
git commit -m "Направить «Как обновить» на страницу загрузки, а не в бота"
```

---

### Task 4: Закрепить контракт сервера и Play Protect в документах

**Files:**
- Modify: `docs/api/README.md` (добавить два эндпоинта)
- Create: `docs/architecture/2026-10-06-distribution-channels.md`
- Modify: `android/README.md` (раздел «Как получить APK» — убрать путь через GitHub Actions Artifacts, оставить страницу загрузки)

**Interfaces:**
- Consumes: `ServerUpdateApi` контракт из Task 1; вердикт Play Protect из спеки.
- Produces: документы.

- [ ] **Step 1: Записать эндпоинты в `docs/api/README.md`**

Добавить раздел «Эндпоинты раздачи (вне подписки)»:

```markdown
## Раздача сборок (не про подписку)

| Метод | Путь | Ответ |
|---|---|---|
| GET | `/app/latest?platform=android` | `{"version": 95, "apk_url": "https://<домен>/app/vpn-95.apk"}`; `404` + `error.code=release_not_found`, если релизов нет |
| GET | `/app/min-supported` | одно число в теле, например `90` |

Клиент синтезирует тег `android-v<version>` сам: формат тега — правило домена,
и сервер не должен заводить второй формат.
```

- [ ] **Step 2: Записать решение о каналах**

Create `docs/architecture/2026-10-06-distribution-channels.md` — краткий свод: основной канал (прямая раздача с нашего сервера), Play Protect (законного обхода нет: апелляция, регистрация пакета, ADB; пользователю — инструкция как пройти предупреждение), альтернативные магазины (Galaxy Store — второй канал; Huawei — нет; RuStore/Play — закрыты), риск 2027. Источник — спека `docs/superpowers/specs/2026-10-06-apk-distribution-and-install-design.md` (не дублировать целиком, сослаться и вынести таблицу решений).

- [ ] **Step 3: Обновить `android/README.md`**

В разделе «Как получить APK и поставить на телефон» заменить инструкцию про GitHub Actions Artifacts на страницу загрузки:

```markdown
Скачать: **<адрес страницы загрузки>** — там номер версии, APK и как пройти
предупреждение Play Protect.

Для автоматической установки на подключённый по USB телефон есть скрипт:
`scripts/install-apk.sh` (по умолчанию берёт последнюю сборку из CI).
```

- [ ] **Step 4: Commit**

```bash
git add docs/api/README.md docs/architecture/2026-10-06-distribution-channels.md android/README.md
git commit -m "Описать контракт раздачи и решение по каналам и Play Protect"
```

---

### Task 5: Действия владельца (не код)

**Files:**
- Create: `docs/architecture/2026-10-06-distribution-owner-actions.md`

**Interfaces:**
- Consumes: вердикт Play Protect из спеки.
- Produces: чеклист, который исполнитель кода выполнить не может.

- [ ] **Step 1: Записать чеклист владельца**

Create `docs/architecture/2026-10-06-distribution-owner-actions.md`:

```markdown
# Раздача: что делает владелец (не код)

Эти шаги требуют аккаунтов, денег или решений владельца — исполнитель кода их
сделать не может. Пока они не сделаны, приложение работает на GitHub-запасе.

1. [ ] Выбрать домен под раздачу (вместо `194-87-252-181.sslip.io:4443`) —
       в ссылке для пользователя `IP:порт` выглядит как фишинг.
2. [ ] Реализовать на сервере `GET /app/latest` и `GET /app/min-supported`
       (контракт — `docs/api/README.md`).
3. [ ] Сделать страницу загрузки `https://<домен>/app`: номер версии, размер,
       ссылка на APK, шаги установки, скриншот про Play Protect.
4. [ ] Ссылку в боте перевести на страницу загрузки (сейчас — на GitHub Release).
5. [ ] Подать апелляцию в Play Protect (`support.google.com/googleplay/android-developer/contact/protectappeals`),
       приложив SHA-256 APK. Решение окончательное, ответа может не быть.
6. [ ] Зарегистрировать пакет в Android Developer Console (ограниченная раздача:
       бесплатно, ≤20 устройств, без гос. ID) — проверить, снимает ли
       предупреждение Play Protect, на одной сборке.
7. [ ] Решить, подавать ли в Galaxy Store (второй канал; VPN там обычно разрешены).
8. [ ] Следить за 2027: глобальная верификация разработчиков расширяется на РФ.
```

- [ ] **Step 2: Commit**

```bash
git add docs/architecture/2026-10-06-distribution-owner-actions.md
git commit -m "Записать шаги владельца по раздаче и Play Protect"
```

---

## Self-Review

**Покрытие спеки:**
- «Размещение скачивания на нашем сервере» → Tasks 1-2 (источник + запас), Task 4 (контракт), Task 5 (сервер реализует владелец). ✓
- «Понятный путь до установки» → Task 3 (кнопка в приложении), Task 4 Step 3 (README: страница вместо GitHub Actions), Task 5 Steps 1-4 (страница, домен, бот). Честно отмечено: основной путь — в боте и на странице, не в приложении. ✓
- «Play Protect» → Task 4 Step 2 (документ каналов), Task 5 Steps 5-8 (действия владельца). ✓

**Осознанно НЕ входит:** механизм скачивания/установки (не меняется), выбор домена (решение владельца), реализация серверных эндпоинтов (вне этого репозитория).

**Согласованность типов:** `ServerUpdateApi(client: ApiClient, platform: String)` — Task 1; используется в Task 2 `AppGraph` как `ServerUpdateApi(apiClient)`. `FallbackUpdateService(primary, fallback)` — Task 2, там же используется. `AppIntent.OpenDownloadPage` — Task 3, объявлен и обработан в одной задаче. ✓

**Плейсхолдеры:** `<домен>` и `<адрес страницы загрузки>` в Task 4/5 — это поля, зависящие от решения владельца (эндпоинт у него же); не отложенная работа исполнителя кода. В Task 1 контракт содержит `<домен>` в примере ответа — заменяется фактическим адресом при реализации сервера. Допустимо, потому что иначе план требовал бы выдумать чужой домен.
