# In-App Update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Дать Android-клиенту узнавать о новой версии из публичного GitHub Release и обновляться через PackageInstaller, привязав показываемую версию к git-сборке.

**Architecture:** Правило разбора тега живёт в `core:domain` (чистый Kotlin, JUnit5). Обращение к GitHub — в `core:network` за интерфейсом `UpdateService` (OkHttp + MockWebServer). Оркестрация проверки, скачивания и установки — в модуле `app`, где уже живёт вся работа с активностью. CI публикует GitHub Release с тегом `android-v<versionCode>` и APK-ассетом.

**Tech Stack:** Kotlin 2.1, AGP 8.7.3, OkHttp 4.12, kotlinx-serialization 1.7.3, JUnit5 5.11.3, MockWebServer 4.12, Compose BOM 2024.12.01, minSdk 26 / targetSdk 35.

## Global Constraints

- **minSdk 26, targetSdk 35, compileSdk 35.** Все API установки должны работать с Android 8.
- **Java/Kotlin toolchain 17.**
- **`core:domain` не имеет Android-зависимостей.** Ни `android.*`, ни OkHttp, ни Compose. Только корутины.
- **Направление зависимостей:** `app` → `feature:*` → `core:*`; `core:network` → `core:domain`. Обратных зависимостей нет.
- **Тесты — JUnit5** (`useJUnitPlatform()`), кроме инструментальных (JUnit4). Имена тестов на русском, в обратных кавычках.
- **Репозиторий:** `impossi8le/vpn-app-design`.
- **Тег релиза:** `android-v<versionCode>`, где `versionCode` = `github.run_number`.
- **Комментарии:** только там, где неочевидна причина. Не описывать «что» делает код.
- **Коммит после каждой задачи.** Сообщение — по-русски, повелительное наклонение, как в истории репозитория.

---

### Task 1: Правило разбора тега и сравнения версий в `core:domain`

**Files:**
- Create: `android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/update/UpdateStatus.kt`
- Test: `android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/update/UpdateStatusTest.kt`

**Interfaces:**
- Consumes: ничего.
- Produces:
  - `fun parseReleaseTag(tag: String): Int?` — `"android-v12"` → `12`, иначе `null`.
  - `sealed interface UpdateStatus` с `UpToDate`, `Unknown`, `data class Available(val versionCode: Int)`.
  - `fun updateStatus(currentVersionCode: Int, latestTag: String?): UpdateStatus`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.impossi8le.vpnapp.domain.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class UpdateStatusTest {

    @ParameterizedTest
    @CsvSource(
        "android-v12, 12",
        "android-v1, 1",
        "android-v4096, 4096",
    )
    fun `тег релиза даёт номер сборки`(tag: String, expected: Int) {
        assertEquals(expected, parseReleaseTag(tag))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "v", "android-v", "android-vX", "v1.0.5", "12", "android 12"])
    fun `чужой или битый тег не даёт номера`(tag: String) {
        assertNull(parseReleaseTag(tag), "«$tag» — не наш тег, номер сборки неизвестен")
    }

    @Test
    fun `версия новее — доступно обновление`() {
        assertEquals(UpdateStatus.Available(13), updateStatus(currentVersionCode = 12, latestTag = "android-v13"))
    }

    @Test
    fun `та же версия — обновление не нужно`() {
        assertEquals(UpdateStatus.UpToDate, updateStatus(currentVersionCode = 12, latestTag = "android-v12"))
    }

    @Test
    fun `старая версия в релизе не тянет назад`() {
        assertEquals(UpdateStatus.UpToDate, updateStatus(currentVersionCode = 12, latestTag = "android-v11"))
    }

    @Test
    fun `неизвестный тег — не знаем, а не да`() {
        // Ключевое: «проверка не удалась» не должно выглядеть как «обновление есть».
        assertEquals(UpdateStatus.Unknown, updateStatus(currentVersionCode = 12, latestTag = "v1.0.5"))
        assertEquals(UpdateStatus.Unknown, updateStatus(currentVersionCode = 12, latestTag = null))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :core:domain:test --tests '*UpdateStatusTest*'`
Expected: FAIL — `Unresolved reference: parseReleaseTag`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.impossi8le.vpnapp.domain.update

private const val TAG_PREFIX = "android-v"

/**
 * Номер сборки из тега релиза. `null` — тег не нашего формата.
 *
 * Релиз выпускается тегом `android-v<versionCode>` (см. CI). Если формат не
 * совпал, сравнивать не с чем: вернуть «обновление есть» здесь означало бы
 * предложить пользователю скачать то, о чём мы ничего не знаем.
 */
fun parseReleaseTag(tag: String): Int? {
    if (!tag.startsWith(TAG_PREFIX)) return null
    val digits = tag.removePrefix(TAG_PREFIX)
    if (digits.isEmpty() || digits.any { !it.isDigit() }) return null
    return digits.toIntOrNull()
}

/** Что известно о свежести сборки. */
sealed interface UpdateStatus {
    /** Установлена последняя версия. */
    data object UpToDate : UpdateStatus

    /** Есть версия новее установленной. */
    data class Available(val versionCode: Int) : UpdateStatus

    /** Ответить не удалось: релиза нет, тег чужой, проверка не прошла. */
    data object Unknown : UpdateStatus
}

/**
 * Нужно ли обновление.
 *
 * `latestTag == null` — проверка не состоялась; это `Unknown`, а не
 * `Available`: тревожить пользователя баннером по несостоявшейся проверке нельзя.
 */
fun updateStatus(currentVersionCode: Int, latestTag: String?): UpdateStatus {
    val latest = latestTag?.let(::parseReleaseTag) ?: return UpdateStatus.Unknown
    return if (latest > currentVersionCode) {
        UpdateStatus.Available(latest)
    } else {
        UpdateStatus.UpToDate
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :core:domain:test --tests '*UpdateStatusTest*'`
Expected: PASS, 5 тестов (два параметризованных разворачиваются в 10 кейсов).

- [ ] **Step 5: Commit**

```bash
git add android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/update/UpdateStatus.kt android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/update/UpdateStatusTest.kt
git commit -m "Решать по тегу релиза, нужна ли новая сборка"
```

---

### Task 2: Привязка версии к git-сборке

**Files:**
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt`
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/AppRoot.kt`
- Modify: `android/feature/account/src/main/kotlin/com/impossi8le/vpnapp/feature/account/AboutScreen.kt`

**Interfaces:**
- Consumes: ничего из Task 1.
- Produces:
  - `BuildConfig.GIT_SHA: String` — короткий SHA коммита сборки.
  - `BuildInfo(versionName: String, versionCode: Int, gitSha: String)` в `AppRoot.kt`.
  - `AboutScreen(appVersion: String, buildSha: String, …)`.

- [ ] **Step 1: Add the BuildConfig field**

В `android/app/build.gradle.kts` внутри блока `defaultConfig { … }`, сразу после строки `versionName = System.getenv("ANDROID_VERSION_NAME") ?: "0.1.0"`, добавить:

```kotlin
        // Короткий SHA коммита: по нему сборка из релиза опознаётся однозначно.
        // Локально (нет GITHUB_SHA) — "dev", это честно: сборка не из git.
        buildConfigField(
            "String",
            "GIT_SHA",
            "\"${System.getenv("GIT_SHA") ?: "dev"}\"",
        )
```

- [ ] **Step 2: Передать GIT_SHA из CI в обе джобы сборки**

В `.github/workflows/android.yml` в шаге `Build signed release` (джоба `release`, блок `env:`) добавить строку:

```yaml
          GIT_SHA: ${{ github.sha }}
```

Затем заменить ту же строку на короткий SHA: `github.sha` длиной 40. В `build.gradle.kts` уже берётся `System.getenv("GIT_SHA")`; чтобы получить 7 символов, в шаге перед сборкой добавить отдельный шаг. Вставить перед `Build signed release`:

```yaml
      - if: steps.signing.outputs.ok == 'true'
        name: Shorten git sha
        run: echo "GIT_SHA=${GITHUB_SHA:0:7}" >> "$GITHUB_ENV"
```

И убрать `GIT_SHA` из блока `env:` шага сборки (переменная теперь приходит из `GITHUB_ENV`).

- [ ] **Step 3: Собрать состояние сборки**

В `android/app/src/main/kotlin/com/impossi8le/vpnapp/AppRoot.kt` рядом с `AppRootState` добавить:

```kotlin
/**
 * Что известно о самой сборке. Заполняется из `BuildConfig`, то есть из того,
 * чем сборку собрал CI (версия и SHA), — а не константой в коде.
 */
data class BuildInfo(
    val versionName: String,
    val versionCode: Int,
    val gitSha: String,
)
```

И добавить поле в `AppRootState` после `val account: …`:

```kotlin
    val build: BuildInfo = BuildInfo(
        versionName = "0.0.0",
        versionCode = 0,
        gitSha = "dev",
    ),
```

- [ ] **Step 4: Заполнить BuildInfo в MainActivity**

В `android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt` в вызове `AppRootState(` после `configs = displayConfigs,` добавить:

```kotlin
            build = BuildInfo(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                gitSha = BuildConfig.GIT_SHA,
            ),
```

Импорт `BuildInfo` не нужен — он в том же пакете `com.impossi8le.vpnapp`.

- [ ] **Step 5: Показать версию и SHA в «О сервисе»**

В `android/feature/account/src/main/kotlin/com/impossi8le/vpnapp/feature/account/AboutScreen.kt` изменить сигнатуру и блок «Сборка»:

```kotlin
@Composable
fun AboutScreen(
    appVersion: String,
    buildSha: String,
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
    modifier: Modifier = Modifier,
) {
```

Блок `SectionLabel(text = "Сборка")` заменить на:

```kotlin
        SectionLabel(text = "Сборка")

        VpnCard {
            Column {
                VpnRow(
                    key = "Версия приложения",
                    value = appVersion,
                    valueMono = true,
                )
                VpnRow(
                    key = "Сборка git",
                    value = buildSha,
                    valueMono = true,
                )
            }
        }
```

- [ ] **Step 6: Прокинуть новые аргументы в AppRoot и убрать дубликат версии**

В `AppRoot.kt` в ветке `AppDestination.About -> AboutScreen(` заменить `appVersion = state.account.appVersion,` на:

```kotlin
                appVersion = state.build.versionName,
                buildSha = state.build.gitSha,
```

В `AppRoot.kt` в `AppRootState` убрать поле `appVersion` из тела `AccountScreenState(...)` — версия теперь только в `build`, чтобы не было двух источников истины. Убрать и из `AccountScreenState` в `AccountScreen.kt`:

В `android/feature/account/src/main/kotlin/com/impossi8le/vpnapp/feature/account/AccountScreen.kt` удалить строку `val appVersion: String,` из `AccountScreenState` и строку `value = state.appVersion,` из разметки карточки аккаунта. В `AppRoot.kt` в дефолте `AppRootState` удалить строку `appVersion = "1.0.0",`. Поле с датой срока жизни сборки не трогать — оно про другую дату. (Позже, 2026-10-06, это поле удалено вместе с обещанием: у даты не было механизма.)

- [ ] **Step 7: Собрать и проверить, что компилируется**

Run: `cd android && ./gradlew :app:compileDebugKotlin :feature:account:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Локально нет JDK — тогда это проверит CI; при этом шаге ничего не выдумывать, просто закоммитить.

- [ ] **Step 8: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt android/app/src/main/kotlin/com/impossi8le/vpnapp/AppRoot.kt android/feature/account/src/main/kotlin/com/impossi8le/vpnapp/feature/account/AboutScreen.kt android/feature/account/src/main/kotlin/com/impossi8le/vpnapp/feature/account/AccountScreen.kt .github/workflows/android.yml
git commit -m "Показывать версию и SHA той сборки, что установлена"
```

---

### Task 3: `UpdateApi` — чтение последнего релиза GitHub

**Files:**
- Create: `android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/update/UpdateService.kt`
- Create: `android/core/network/src/main/kotlin/com/impossi8le/vpnapp/network/UpdateApi.kt`
- Test: `android/core/network/src/test/kotlin/com/impossi8le/vpnapp/network/UpdateApiTest.kt`

**Interfaces:**
- Consumes: `ApiClient` (существующий), `parseReleaseTag` (Task 1).
- Produces:
  - `data class ReleaseInfo(val tagName: String, val apkUrl: String)` в `core:domain`.
  - `sealed interface UpdateError` с `NotFound`, `NetworkUnavailable`, `RateLimited`, `Unexpected(val statusCode: Int)`.
  - `class UpdateException(val error: UpdateError) : Exception`.
  - `interface UpdateService { suspend fun latestRelease(): Result<ReleaseInfo> }`.
  - `class UpdateApi(client: ApiClient, baseUrl: String = GITHUB_RELEASES_URL) : UpdateService`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.update.UpdateError
import com.impossi8le.vpnapp.domain.update.UpdateException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UpdateApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: UpdateApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = UpdateApi(
            ApiClient(baseUrl = server.url("/").toString().trimEnd('/')),
            releasesUrl = server.url("/repos/impossi8le/vpn-app-design/releases/latest").toString(),
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `релиз с apk-ассетом разбирается в тег и ссылку`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"tag_name":"android-v42","assets":[
                  {"name":"vpn-app-android-42.apk",
                   "browser_download_url":"https://example.com/app.apk"}
                ]}
                """.trimIndent(),
            ),
        )

        val info = api.latestRelease().getOrThrow()
        assertEquals("android-v42", info.tagName)
        assertEquals("https://example.com/app.apk", info.apkUrl)
    }

    @Test
    fun `релиз без apk-ассета — не обновление, а сбой ответа`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"tag_name":"android-v42","assets":[{"name":"notes.txt","browser_download_url":"https://example.com/n.txt"}]}"""),
        )

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        // Скачивать нечего — предлагать обновление с пустой ссылкой нельзя.
        assertTrue(error is UpdateError.Unexpected, "ассета нет => ответ неполный, было $error")
    }

    @Test
    fun `нет релизов — это не ошибка связи`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        assertEquals(UpdateError.NotFound, error)
    }

    @Test
    fun `лимит анонимных запросов отличается от отсутствия релиза`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"message":"API rate limit exceeded"}"""),
        )

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        assertEquals(UpdateError.RateLimited, error)
    }

    @Test
    fun `битое тело не роняет парсер`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("не json"))

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        assertTrue(error is UpdateError.Unexpected || error is UpdateError.NetworkUnavailable)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :core:network:test --tests '*UpdateApiTest*'`
Expected: FAIL — `Unresolved reference: UpdateApi`.

- [ ] **Step 3: Write the domain contract**

```kotlin
package com.impossi8le.vpnapp.domain.update

/**
 * Данные последнего релиза, как их отдал GitHub.
 *
 * Номер сборки здесь НЕ извлекается: разбор тега — правило домена
 * ([parseReleaseTag]), и оно должно тестироваться без сети.
 */
data class ReleaseInfo(val tagName: String, val apkUrl: String)

/** Почему не удалось узнать последнюю версию. */
sealed interface UpdateError {
    /** Релизов ещё нет — это не сбой, просто обновляться не с чего. */
    data object NotFound : UpdateError

    /** Нет связи или таймаут: повтор осмыслен. */
    data object NetworkUnavailable : UpdateError

    /** Исчерпан лимит анонимных запросов к GitHub API. */
    data object RateLimited : UpdateError

    /** Прочее: ответ не разобрался или в нём нет APK. */
    data class Unexpected(val statusCode: Int) : UpdateError
}

class UpdateException(val error: UpdateError) :
    Exception("не удалось узнать последнюю версию: $error")

/** Источник сведений о последней версии. */
interface UpdateService {
    suspend fun latestRelease(): Result<ReleaseInfo>
}
```

- [ ] **Step 4: Write the implementation**

```kotlin
package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.update.ReleaseInfo
import com.impossi8le.vpnapp.domain.update.UpdateError
import com.impossi8le.vpnapp.domain.update.UpdateException
import com.impossi8le.vpnapp.domain.update.UpdateService
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

/** Последний релиз публичного репозитория. Домена у сервиса нет (см. §10.3). */
const val GITHUB_RELEASES_URL =
    "https://api.github.com/repos/impossi8le/vpn-app-design/releases/latest"

/**
 * Чтение последнего релиза GitHub.
 *
 * Анонимно: релиз публичный, токен не нужен. Обратная сторона — лимит запросов
 * на IP, поэтому проверка вызывается при запуске и вручную, а не в цикле.
 */
class UpdateApi(
    private val client: ApiClient,
    private val releasesUrl: String = GITHUB_RELEASES_URL,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : UpdateService {

    override suspend fun latestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(releasesUrl)
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()

            client.http.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> Result.failure(UpdateException(UpdateError.NotFound))
                    response.code == 403 -> Result.failure(UpdateException(UpdateError.RateLimited))
                    !response.isSuccessful -> Result.failure(
                        UpdateException(UpdateError.Unexpected(response.code)),
                    )
                    else -> Result.success(parseRelease(response.body?.string().orEmpty()))
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

    /**
     * Разбор ответа. Ассет ищем по расширению `.apk`: имя файла меняется от
     * сборки к сборке, а расширение — нет.
     */
    private fun parseRelease(body: String): ReleaseInfo {
        val root = json.parseToJsonElement(body).jsonObject
        val tag = root["tag_name"]?.toString()?.trim('"').orEmpty()
        val apkUrl = root["assets"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .firstOrNull { asset ->
                asset["name"]?.toString()?.trim('"')?.endsWith(".apk") == true
            }
            ?.get("browser_download_url")
            ?.toString()
            ?.trim('"')
        if (tag.isEmpty() || apkUrl.isNullOrEmpty()) {
            throw UpdateException(UpdateError.Unexpected(statusCode = 200))
        }
        return ReleaseInfo(tagName = tag, apkUrl = apkUrl)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `cd android && ./gradlew :core:network:test --tests '*UpdateApiTest*'`
Expected: PASS, 5 тестов.

- [ ] **Step 6: Commit**

```bash
git add android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/update/UpdateService.kt android/core/network/src/main/kotlin/com/impossi8le/vpnapp/network/UpdateApi.kt android/core/network/src/test/kotlin/com/impossi8le/vpnapp/network/UpdateApiTest.kt
git commit -m "Читать последний релиз GitHub как источник версии"
```

---

### Task 4: CI публикует релиз с APK

**Files:**
- Modify: `.github/workflows/android.yml`

**Interfaces:**
- Consumes: `ANDROID_VERSION_CODE` (уже вычисляется в джобе `release`).
- Produces: GitHub Release с тегом `android-v<versionCode>` и APK-ассетом.

- [ ] **Step 1: Выдать workflow право на запись**

В начале `.github/workflows/android.yml`, после блока `concurrency:` и перед `jobs:`, добавить:

```yaml
# Создание релиза требует записи в contents. Без этого блока GITHUB_TOKEN
# read-only, и gh release create падает на 403.
permissions:
  contents: write
```

- [ ] **Step 2: Публиковать релиз после загрузки артефакта**

В конце джобы `release`, после шага `Upload APK`, добавить:

```yaml
      # Релиз — то, что читает приложение при проверке обновления. Тег несёт
      # номер сборки: android-v<N>, где N — versionCode. Заливка повторного
      # тега не удалась бы, поэтому существующий релиз обновляем.
      - if: steps.signing.outputs.ok == 'true'
        name: Publish release
        working-directory: android
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          TAG: android-v${{ github.run_number }}
        run: |
          set -euo pipefail
          apk=$(ls app/build/outputs/apk/release/*.apk)
          if gh release view "$TAG" >/dev/null 2>&1; then
            gh release upload "$TAG" "$apk" --clobber
          else
            gh release create "$TAG" "$apk" \
              --title "1.0.${{ github.run_number }}" \
              --notes "Сборка ${GITHUB_SHA:0:7}. Подписана ключом релиза."
          fi
```

- [ ] **Step 3: Проверить синтаксис workflow**

Run: `python3 -c "import yaml,sys; yaml.safe_load(open('.github/workflows/android.yml')); print('ok')"`
Expected: `ok`. Если PyYAML недоступен — проверить отступы вручную: блок `permissions:` на нулевом уровне, шаг `Publish release` — на уровне соседнего `Upload APK` (шесть пробелов перед `-`).

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/android.yml
git commit -m "Публиковать подписанный APK как GitHub Release"
```

---

### Task 5: Скачивание APK во внутренний кеш

**Files:**
- Create: `android/app/src/main/kotlin/com/impossi8le/vpnapp/update/ApkDownloader.kt`
- Test: `android/app/src/test/kotlin/com/impossi8le/vpnapp/update/ApkDownloaderTest.kt`

**Interfaces:**
- Consumes: `ApiClient.http` (OkHttp), `Result`.
- Produces:
  - `class ApkDownloader(private val http: OkHttpClient, private val cacheDir: File)`
  - `suspend fun download(url: String, onProgress: (Int) -> Unit = {}): Result<File>`
  - `fun clearStale(): Unit` — удаляет недокачанные `.apk.part`.

- [ ] **Step 1: Write the failing test**

`android/app/build.gradle.kts` уже не имеет `mockwebserver` в `testImplementation`. Добавить перед `testImplementation(libs.junit5.api)`:

```kotlin
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
```

Затем тест:

```kotlin
package com.impossi8le.vpnapp.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ApkDownloaderTest {

    private lateinit var server: MockWebServer

    @TempDir
    lateinit var cacheDir: File

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun downloader() = ApkDownloader(OkHttpClient(), cacheDir)

    @Test
    fun `скачанный apk лежит в кеше целиком`() = runTest {
        val payload = ByteArray(2048) { it.toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload)))

        val file = downloader().download(server.url("/app.apk").toString()).getOrThrow()

        assertTrue(file.exists(), "файл должен остаться на диске")
        assertTrue(file.readBytes().contentEquals(payload), "содержимое должно совпасть побайтово")
        assertTrue(file.name.endsWith(".apk"), "имя должно нести .apk: ${file.name}")
    }

    @Test
    fun `прогресс доходит до 100`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(1024)))
        val percents = mutableListOf<Int>()

        downloader().download(server.url("/app.apk").toString()) { percents += it }.getOrThrow()

        assertEquals(100, percents.last())
    }

    @Test
    fun `обрыв не оставляет половину файла под видом готового`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = downloader().download(server.url("/app.apk").toString())

        assertTrue(result.isFailure, "500 — это провал, а не файл")
        assertTrue(
            cacheDir.listFiles().orEmpty().none { it.extension == "apk" },
            "недокачанный файл не должен выглядеть готовым: ${cacheDir.list()?.toList()}",
        )
    }

    @Test
    fun `clearStale убирает только недокачанное`(){
        val part = File(cacheDir, "update.apk.part").apply { writeText("half") }
        val kept = File(cacheDir, "keep.txt").apply { writeText("x") }

        ApkDownloader(OkHttpClient(), cacheDir).clearStale()

        assertTrue(!part.exists(), ".part должен быть удалён")
        assertTrue(kept.exists(), "посторонние файлы не трогаем")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*ApkDownloaderTest*'`
Expected: FAIL — `Unresolved reference: ApkDownloader`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.impossi8le.vpnapp.update

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val APK_NAME = "update.apk"
private const val PART_SUFFIX = ".part"

/**
 * Скачивание APK обновления во внутренний кеш.
 *
 * Пишем сначала в `.part`, и лишь по завершении переименовываем в `.apk`:
 * оборванная закачка не должна оставить файл, который установщик примет за
 * готовый. `clearStale()` убирает такие огрызки при следующем запуске.
 */
class ApkDownloader(
    private val http: OkHttpClient,
    private val cacheDir: File,
) {

    suspend fun download(url: String, onProgress: (Int) -> Unit = {}): Result<File> =
        withContext(Dispatchers.IO) {
            val target = File(cacheDir, APK_NAME)
            val part = File(cacheDir, APK_NAME + PART_SUFFIX)
            try {
                val request = Request.Builder().url(url).get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("сервер отдал ${response.code}"),
                        )
                    }
                    val total = response.body?.contentLength() ?: -1L
                    response.body?.byteStream()?.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var written = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                written += read
                                if (total > 0) {
                                    onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
                                }
                            }
                        }
                    }
                }
                if (totalMissingOrShort(part)) {
                    part.delete()
                    return@withContext Result.failure(IOException("файл скачался пустым"))
                }
                if (target.exists()) target.delete()
                part.renameTo(target)
                onProgress(100)
                Result.success(target)
            } catch (e: IOException) {
                part.delete()
                Result.failure(e)
            }
        }

    private fun totalMissingOrShort(file: File): Boolean = !file.exists() || file.length() == 0L

    /** Убрать недокачанные файлы прошлых попыток. */
    fun clearStale() {
        cacheDir.listFiles()
            ?.filter { it.name.endsWith(PART_SUFFIX) }
            ?.forEach { it.delete() }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*ApkDownloaderTest*'`
Expected: PASS, 4 теста.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/com/impossi8le/vpnapp/update/ApkDownloader.kt android/app/src/test/kotlin/com/impossi8le/vpnapp/update/ApkDownloaderTest.kt android/app/build.gradle.kts
git commit -m "Скачивать APK обновления во внутренний кеш"
```

---

### Task 6: Установка APK через PackageInstaller

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`
- Create: `android/app/src/main/res/xml/file_paths.xml`
- Create: `android/app/src/main/kotlin/com/impossi8le/vpnapp/update/ApkInstaller.kt`

**Interfaces:**
- Consumes: `File` из Task 5.
- Produces: `class ApkInstaller(private val context: Context)` с
  - `fun canInstall(): Boolean`
  - `fun unknownSourcesIntent(): Intent`
  - `fun install(apk: File): Intent` — намерение для `startActivity`.

- [ ] **Step 1: Разрешить установку и открыть FileProvider**

В `android/app/src/main/AndroidManifest.xml` после строки с `ACCESS_NETWORK_STATE` добавить разрешение, а внутрь `<application>` перед `<activity` — провайдер:

```xml
    <!-- Установка скачанного обновления: система спросит подтверждение сама. -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

```xml
        <!-- APK отдаётся установщику как content:// — file:// на Android 7+
             бросает FileUriExposedException. Каталог ограничен кешем. -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

Создать `android/app/src/main/res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="updates" path="." />
</paths>
```

- [ ] **Step 2: Написать установщик**

```kotlin
package com.impossi8le.vpnapp.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * Установка скачанного APK.
 *
 * Подпись не проверяем вручную: система сама отвергнет APK с чужим ключом, и
 * установка «поверх» просто не пройдёт. Наша часть — отдать файл через
 * FileProvider и, если пользователь запретил установку из неизвестных
 * источников, показать ему, где это включить: молчаливая кнопка хуже отказа.
 */
class ApkInstaller(private val context: Context) {

    /** Разрешена ли установка из этого приложения. */
    fun canInstall(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Экран настроек «Установка неизвестных приложений» для этого пакета. */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * Намерение установки. Отдельным методом, а не `install()` с побочным
     * эффектом: запускать активность должен экран, у него есть контекст и
     * обработка `ActivityNotFoundException`.
     */
    fun install(apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
```

- [ ] **Step 3: Проверить, что манифест линкуется**

Run: `cd android && ./gradlew :app:processDebugMainManifest`
Expected: BUILD SUCCESSFUL. Ошибка «FileProvider not found» означает отсутствие `androidx.core:core-ktx` — он приходит транзитивно через `activity-compose`; если его нет, добавить `implementation(libs.androidx.core.ktx)` в каталог.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/AndroidManifest.xml android/app/src/main/res/xml/file_paths.xml android/app/src/main/kotlin/com/impossi8le/vpnapp/update/ApkInstaller.kt
git commit -m "Устанавливать обновление через PackageInstaller"
```

---

### Task 7: Оркестрация проверки в `UpdateChecker`

**Files:**
- Create: `android/app/src/main/kotlin/com/impossi8le/vpnapp/update/UpdateChecker.kt`
- Test: `android/app/src/test/kotlin/com/impossi8le/vpnapp/update/UpdateCheckerTest.kt`

**Interfaces:**
- Consumes: `UpdateService`, `updateStatus`, `UpdateStatus` (Task 1, 3), `ApkDownloader` (Task 5).
- Produces:
  - `sealed interface UpdateUiState` с `Idle`, `Checking`, `UpToDate`, `Available(versionCode: Int)`, `Failed(reason: String)`.
  - `class UpdateChecker(currentVersionCode: Int, service: UpdateService)`
  - `suspend fun check(): UpdateUiState`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.impossi8le.vpnapp.update

import com.impossi8le.vpnapp.domain.update.ReleaseInfo
import com.impossi8le.vpnapp.domain.update.UpdateError
import com.impossi8le.vpnapp.domain.update.UpdateException
import com.impossi8le.vpnapp.domain.update.UpdateService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FakeService(private val result: Result<ReleaseInfo>) : UpdateService {
    override suspend fun latestRelease(): Result<ReleaseInfo> = result
}

class UpdateCheckerTest {

    @Test
    fun `новая версия в релизе — состояние Available`() = runTest {
        val checker = UpdateChecker(
            currentVersionCode = 12,
            service = FakeService(Result.success(ReleaseInfo("android-v13", "https://e/a.apk"))),
        )

        assertEquals(UpdateUiState.Available(13), checker.check())
    }

    @Test
    fun `та же версия — UpToDate`() = runTest {
        val checker = UpdateChecker(
            currentVersionCode = 12,
            service = FakeService(Result.success(ReleaseInfo("android-v12", "https://e/a.apk"))),
        )

        assertEquals(UpdateUiState.UpToDate, checker.check())
    }

    @Test
    fun `чужой тег — Failed, а не «обновление есть»`() = runTest {
        val checker = UpdateChecker(
            currentVersionCode = 12,
            service = FakeService(Result.success(ReleaseInfo("v1.0.5", "https://e/a.apk"))),
        )

        // Ключевое: неизвестность не выдаём за обновление.
        assertTrue(checker.check() is UpdateUiState.Failed)
    }

    @Test
    fun `нет релизов — UpToDate, а не ошибка`() = runTest {
        val checker = UpdateChecker(
            currentVersionCode = 12,
            service = FakeService(Result.failure(UpdateException(UpdateError.NotFound))),
        )

        assertEquals(UpdateUiState.UpToDate, checker.check())
    }

    @Test
    fun `сеть недоступна — Failed с причиной`() = runTest {
        val checker = UpdateChecker(
            currentVersionCode = 12,
            service = FakeService(Result.failure(UpdateException(UpdateError.NetworkUnavailable))),
        )

        val state = checker.check()
        assertTrue(state is UpdateUiState.Failed, "нет связи — честное «не удалось проверить», было $state")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*UpdateCheckerTest*'`
Expected: FAIL — `Unresolved reference: UpdateChecker`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.impossi8le.vpnapp.update

import com.impossi8le.vpnapp.domain.update.UpdateError
import com.impossi8le.vpnapp.domain.update.UpdateException
import com.impossi8le.vpnapp.domain.update.UpdateService
import com.impossi8le.vpnapp.domain.update.UpdateStatus
import com.impossi8le.vpnapp.domain.update.updateStatus

/** Что показать пользователю по итогам проверки. */
sealed interface UpdateUiState {
    /** Проверка ещё не запускалась. */
    data object Idle : UpdateUiState

    /** Проверка идёт. */
    data object Checking : UpdateUiState

    /** Обновляться не нужно. */
    data object UpToDate : UpdateUiState

    /** Есть версия новее. */
    data class Available(val versionCode: Int) : UpdateUiState

    /** Проверить не удалось: показываем причину, а не «версия свежая». */
    data class Failed(val reason: String) : UpdateUiState
}

/**
 * Сведение ответа сервиса и правила домена в состояние экрана.
 *
 * Различие «обновления нет» и «проверить не удалось» здесь принципиально:
 * первое — утверждение о свежести, второе — честное незнание. Показать второе
 * как первое значило бы сказать пользователю, что у него всё актуально, не имея
 * на это оснований.
 */
class UpdateChecker(
    private val currentVersionCode: Int,
    private val service: UpdateService,
) {

    suspend fun check(): UpdateUiState {
        val result = service.latestRelease()
        val error = (result.exceptionOrNull() as? UpdateException)?.error
        if (error != null) {
            return when (error) {
                // Релизов нет — обновляться не с чего, это не сбой проверки.
                UpdateError.NotFound -> UpdateUiState.UpToDate
                UpdateError.NetworkUnavailable -> UpdateUiState.Failed("нет связи с GitHub")
                UpdateError.RateLimited -> UpdateUiState.Failed("GitHub ограничил запросы, попробуйте позже")
                is UpdateError.Unexpected -> UpdateUiState.Failed("GitHub ответил неожиданно")
            }
        }

        val info = result.getOrNull() ?: return UpdateUiState.Failed("не удалось проверить")
        return when (val status = updateStatus(currentVersionCode, info.tagName)) {
            is UpdateStatus.Available -> UpdateUiState.Available(status.versionCode)
            UpdateStatus.UpToDate -> UpdateUiState.UpToDate
            UpdateStatus.Unknown -> UpdateUiState.Failed("формат релиза не распознан")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests '*UpdateCheckerTest*'`
Expected: PASS, 5 тестов.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/com/impossi8le/vpnapp/update/UpdateChecker.kt android/app/src/test/kotlin/com/impossi8le/vpnapp/update/UpdateCheckerTest.kt
git commit -m "Отличать «обновление есть» от «проверить не удалось»"
```

---

### Task 8: Строка обновления в «О сервисе» и баннер на главном

**Files:**
- Modify: `android/feature/account/src/main/kotlin/com/impossi8le/vpnapp/feature/account/AboutScreen.kt`
- Modify: `android/feature/home/src/main/kotlin/com/impossi8le/vpnapp/feature/home/ConnectionScreen.kt`
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/AppRoot.kt`
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/AppGraph.kt`
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt`

**Interfaces:**
- Consumes: `UpdateChecker`, `UpdateUiState`, `ApkDownloader`, `ApkInstaller`, `UpdateApi`.
- Produces: рабочий контур «узнать → скачать → установить».

- [ ] **Step 1: Добавить состояния в AppRoot**

В `AppRoot.kt` импортировать `com.impossi8le.vpnapp.update.UpdateUiState` и добавить в `AppRootState` после `val build: BuildInfo = …`:

```kotlin
    /** Состояние проверки обновления. Показывается в «О сервисе» и баннером. */
    val update: UpdateUiState = UpdateUiState.Idle,
    /** Прогресс скачивания APK, `null` — не качаем. */
    val updateProgress: Int? = null,
    /**
     * Почему установка не началась или не удалась. Показывается в «О сервисе».
     * `null` — причины нет.
     */
    val updateMessage: String? = null,
    /** Баннер обновления закрыт пользователем. */
    val updateBannerDismissed: Boolean = false,
```

В `AppIntent` добавить:

```kotlin
    data object CheckForUpdate : AppIntent
    data object DownloadUpdate : AppIntent
    data object DismissUpdateBanner : AppIntent
```

- [ ] **Step 2: Показать в AboutScreen**

Изменить сигнатуру, добавив параметры после `buildSha`:

```kotlin
    update: UpdateUiState,
    updateProgress: Int?,
    updateMessage: String?,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
```

Импортировать `com.impossi8le.vpnapp.update.UpdateUiState`. После блока «Сборка» добавить:

```kotlin
        SectionLabel(text = "Обновление")

        VpnCard {
            when {
                updateProgress != null -> VpnRow(
                    key = "Скачивание обновления",
                    value = "$updateProgress%",
                    valueMono = true,
                )
                // Сообщение о сорвавшейся установке важнее состояния проверки:
                // пользователь уже нажал «Скачать» и ждёт объяснения.
                updateMessage != null -> VpnRow(
                    key = "Обновление",
                    value = updateMessage,
                )
                update is UpdateUiState.Available -> Column {
                    VpnRow(
                        key = "Доступна версия",
                        value = "1.0.${update.versionCode}",
                        valueMono = true,
                    )
                    VpnRow(
                        key = "Установить обновление",
                        trailing = {
                            LinkAction("Скачать ›", onDownloadUpdate, ABOUT_UPDATE_TAG)
                        },
                    )
                }
                update is UpdateUiState.Checking -> VpnRow(key = "Проверка обновлений", value = "идёт…")
                update is UpdateUiState.Failed -> VpnRow(
                    key = "Проверка обновлений",
                    value = update.reason,
                )
                update is UpdateUiState.UpToDate -> VpnRow(
                    key = "Проверка обновлений",
                    value = "установлена последняя",
                )
                else -> VpnRow(
                    key = "Проверка обновлений",
                    trailing = {
                        LinkAction("Проверить ›", onCheckUpdate, ABOUT_CHECK_UPDATE_TAG)
                    },
                )
            }
        }
```

Добавить константы рядом с существующими:

```kotlin
const val ABOUT_UPDATE_TAG = "about_update"
const val ABOUT_CHECK_UPDATE_TAG = "about_check_update"
```

- [ ] **Step 3: Показать баннер на главном экране**

В `ConnectionScreen.kt` добавить в сигнатуру параметры и в начало `Column` — баннер:

```kotlin
    updateVersionCode: Int?,
    onDownloadUpdate: () -> Unit,
    onDismissUpdateBanner: () -> Unit,
```

```kotlin
        if (updateVersionCode != null) {
            VpnCard {
                Column {
                    Text(
                        text = "Доступна новая версия 1.0.$updateVersionCode",
                        color = VpnColors.TextPrimary,
                        fontSize = 15.sp,
                    )
                    Row {
                        BannerAction("Обновить", onDownloadUpdate, HOME_UPDATE_TAG)
                        BannerAction("Позже", onDismissUpdateBanner, HOME_UPDATE_DISMISS_TAG)
                    }
                }
            }
        }
```

`LinkAction` объявлен `internal` в модуле `feature:account` и отсюда недоступен, поэтому в `ConnectionScreen.kt` рядом с экраном объявить свою ссылку:

```kotlin
const val HOME_UPDATE_TAG = "home_update"
const val HOME_UPDATE_DISMISS_TAG = "home_update_dismiss"

@Composable
private fun BannerAction(text: String, onClick: () -> Unit, testTag: String) {
    Text(
        text = text,
        color = VpnColors.Accent,
        fontSize = 15.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
            .testTag(testTag),
    )
}
```

Импорты, которых ещё может не быть в файле: `androidx.compose.foundation.clickable`, `androidx.compose.ui.platform.testTag`, `androidx.compose.ui.unit.dp`, `androidx.compose.ui.unit.sp`, `com.impossi8le.vpnapp.core.ui.VpnCard`. Если цвета `VpnColors.Accent` нет — посмотреть, каким цветом в `ConnectionScreen.kt` уже нарисованы нажимаемые надписи, и взять тот же (не выдумывать новый).

- [ ] **Step 4: Прокинуть состояние и намерения через AppRoot**

В ветке `AppDestination.About -> AboutScreen(` добавить:

```kotlin
                update = state.update,
                updateProgress = state.updateProgress,
                updateMessage = state.updateMessage,
                onCheckUpdate = { onIntent(AppIntent.CheckForUpdate) },
                onDownloadUpdate = { onIntent(AppIntent.DownloadUpdate) },
```

В ветке `AppDestination.Connection -> ConnectionScreen(` добавить:

```kotlin
                updateVersionCode = (state.update as? UpdateUiState.Available)
                    ?.takeUnless { state.updateBannerDismissed }?.versionCode,
                onDownloadUpdate = { onIntent(AppIntent.DownloadUpdate) },
                onDismissUpdateBanner = { onIntent(AppIntent.DismissUpdateBanner) },
```

- [ ] **Step 5: Собрать зависимость в AppGraph**

В `AppGraph.kt` добавить поле и создание:

```kotlin
    val updateApi: UpdateApi = UpdateApi(apiClient)
```

Импорт `com.impossi8le.vpnapp.network.UpdateApi`.

- [ ] **Step 6: Связать всё в MainActivity**

Добавить импорты `com.impossi8le.vpnapp.update.ApkDownloader`, `ApkInstaller`, `UpdateChecker`, `UpdateUiState`, `java.io.File` (уже есть).

Внутри `VpnApp()`, после создания `graph`, добавить состояние и хелперы:

```kotlin
    val updateChecker = remember {
        UpdateChecker(BuildConfig.VERSION_CODE, graph.updateApi)
    }
    val downloader = remember { ApkDownloader(graph.apiClient.http, context.cacheDir) }
    val installer = remember { ApkInstaller(context) }

    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var updateProgress by remember { mutableStateOf<Int?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var bannerDismissed by remember { mutableStateOf(false) }

    // Огрызки прошлых закачек не должны переживать запуск.
    LaunchedEffect(Unit) { downloader.clearStale() }

    // Проверка при запуске. Не в цикле: анонимный лимит GitHub невелик.
    LaunchedEffect(Unit) {
        updateState = UpdateUiState.Checking
        updateState = updateChecker.check()
    }
```

Добавить установку APK через launcher:

```kotlin
    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* результат установки система показывает сама */ }
```

В блок `onIntent = { intent -> when (intent) {` добавить ветки (перед `is AppIntent.ConnectionAction`):

```kotlin
                AppIntent.CheckForUpdate -> scope.launch {
                    updateState = UpdateUiState.Checking
                    updateState = updateChecker.check()
                }
                AppIntent.DownloadUpdate -> scope.launch {
                    val state = updateState
                    if (state !is UpdateUiState.Available) return@launch
                    val info = graph.updateApi.latestRelease().getOrNull()
                    if (info == null) {
                        updateMessage = "не удалось получить ссылку на обновление"
                        return@launch
                    }
                    if (!installer.canInstall()) {
                        // Не молчим: показываем, где включить установку.
                        updateMessage = "разрешите установку из этого источника в настройках"
                        runCatching { installLauncher.launch(installer.unknownSourcesIntent()) }
                        return@launch
                    }
                    updateProgress = 0
                    val file = downloader.download(info.apkUrl) { updateProgress = it }.getOrNull()
                    updateProgress = null
                    if (file == null) {
                        updateMessage = "скачивание не удалось"
                        return@launch
                    }
                    updateMessage = null
                    installLauncher.launch(installer.install(file))
                }
                AppIntent.DismissUpdateBanner -> bannerDismissed = true
```

И передать в `AppRootState(`:

```kotlin
            update = updateState,
            updateProgress = updateProgress,
            updateMessage = updateMessage,
            updateBannerDismissed = bannerDismissed,
```

`updateLauncher` должен быть объявлен до `AppRootState(...)` — `rememberLauncherForActivityResult` нельзя вызывать после условных выражений, поэтому объявить рядом с `consentLauncher`.

- [ ] **Step 7: Собрать**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add android/feature/account/src/main/kotlin/com/impossi8le/vpnapp/feature/account/AboutScreen.kt android/feature/home/src/main/kotlin/com/impossi8le/vpnapp/feature/home/ConnectionScreen.kt android/app/src/main/kotlin/com/impossi8le/vpnapp/AppRoot.kt android/app/src/main/kotlin/com/impossi8le/vpnapp/AppGraph.kt android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt
git commit -m "Показать доступное обновление и дать его установить"
```

---

### Task 9: Проверка контура целиком

**Files:**
- Modify: `docs/superpowers/specs/2026-10-05-in-app-update-design.md` (отметить статус)

**Interfaces:**
- Consumes: всё предыдущее.
- Produces: подтверждение, что контур работает.

- [ ] **Step 1: Прогнать все юнит-тесты**

Run: `cd android && ./gradlew test --no-daemon`
Expected: BUILD SUCCESSFUL, счётчик тестов больше, чем до правок.

- [ ] **Step 2: Убедиться, что новые тесты действительно выполнились**

Run: `cd android && find . -path '*/test-results/*' -name 'TEST-*Update*.xml'`
Expected: как минимум `TEST-...UpdateStatusTest.xml`, `...UpdateApiTest.xml`, `...UpdateCheckerTest.xml`, `...ApkDownloaderTest.xml`. Пустой список означает, что тесты не запускались, — это провал, а не успех.

- [ ] **Step 3: Обновить статус спецификации**

В `docs/superpowers/specs/2026-10-05-in-app-update-design.md` заменить строку статуса на:

```markdown
Дата: 2026-10-05. Статус: **реализовано**, проверено юнит-тестами.
```

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-10-05-in-app-update-design.md
git commit -m "Отметить обновление из приложения реализованным"
```

---

## Что остаётся человеку

Эти шаги нельзя выполнить в этой среде — их нужно явно назвать при завершении:

1. **Проверить на телефоне**, что баннер появляется и APK устанавливается поверх.
2. **Убедиться, что GitHub Release создался** после первого прогона CI на `main` с заданными секретами подписи.
3. **Проверить доступность `api.github.com` из РФ-сети** — риск, зафиксированный в спецификации.
