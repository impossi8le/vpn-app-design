# Wiring the Real API into the Android Client — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Подключить реальный бэкенд к Android-клиенту так, чтобы вход, список подключений и загрузка `.ovpn` работали вживую, а не через `MockApi`.

**Architecture:** `AppGraph` в `:app` собирает сетевые API, хранилища и координаторы. Профиль качается один раз, лежит в `filesDir` рядом с метаданными (`profile.meta`) и переиспользуется без запросов; удаляется по истечении срока. Домен не знает про Android; зелёный статус по-прежнему рождается только из замера (§6).

**Tech Stack:** Kotlin, Jetpack Compose, OkHttp, kotlinx.serialization, EncryptedSharedPreferences, JUnit5 (JVM) / JUnit4 (инструментальные).

## Global Constraints

- Base URL: `https://194-87-252-181.sslip.io:4443/api/v1` — одна константа, TLS включён, IP не пинить.
- Инвариант §6: зелёный `Protected` рождается только в `ProtectionVerdict.evaluate`; `AppGraph`/`ConfigManager` его породить не могут. Не ослаблять.
- Доменные модули (`core:domain`, `core:config`) не зависят от `core:network` (направление зависимостей §1).
- JVM-тесты — JUnit5; инструментальные — JUnit4, в пиках DEX **нельзя backtick-имена** (только camelCase).
- Никогда не заворачивать вывод Gradle в конвейер (`| tee`): это ложная зелень. Писать `> log 2>&1; status=$?; cat log; exit $status`.
- Локальная сборка (быстрее CI): `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew <task> --no-daemon`.
- Профиль пишется в `filesDir/profile.ovpn` — путь уже читает `VpnTunnelService` и `AppTunnelController`.

---

### Task 1: Домен — метаданные профиля и решение о переиспользовании

**Files:**
- Create: `android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/config/ProfileMeta.kt`
- Test: `android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/config/ProfileReuseTest.kt`

**Interfaces:**
- Consumes: `SubscriptionStatus` (уже есть в `domain/config/Config.kt`).
- Produces: `ProfileMeta(configId: String, version: String, endDateEpochSeconds: Long)`, `interface ProfileMetaStore { load(): ProfileMeta?; save(meta: ProfileMeta); clear() }`, `enum class ProfileUse { Missing, Expired, Reusable }`, `fun decideProfileUse(hasProfile: Boolean, meta: ProfileMeta?, requestedConfigId: String, nowEpochSeconds: Long): ProfileUse`.

- [ ] **Step 1: Написать падающий тест**

```kotlin
package com.impossi8le.vpnapp.domain.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ProfileReuseTest {

    private val meta = ProfileMeta(
        configId = "GEclient94",
        version = "2026-10-02T12:45:56Z",
        endDateEpochSeconds = 1_000_000L,
    )

    @Test
    fun `профиля нет — нужен запрос`() {
        assertEquals(
            ProfileUse.Missing,
            decideProfileUse(hasProfile = false, meta = meta, requestedConfigId = "GEclient94", nowEpochSeconds = 0L),
        )
    }

    @Test
    fun `метаданных нет — нужен запрос`() {
        assertEquals(
            ProfileUse.Missing,
            decideProfileUse(hasProfile = true, meta = null, requestedConfigId = "GEclient94", nowEpochSeconds = 0L),
        )
    }

    @Test
    fun `другой конфиг — нужен запрос`() {
        assertEquals(
            ProfileUse.Missing,
            decideProfileUse(hasProfile = true, meta = meta, requestedConfigId = "FIclient07", nowEpochSeconds = 0L),
        )
    }

    @Test
    fun `срок вышел — профиль недействителен`() {
        assertEquals(
            ProfileUse.Expired,
            decideProfileUse(hasProfile = true, meta = meta, requestedConfigId = "GEclient94", nowEpochSeconds = 1_000_000L),
        )
    }

    @Test
    fun `срок впереди — профиль годится повторно`() {
        assertEquals(
            ProfileUse.Reusable,
            decideProfileUse(hasProfile = true, meta = meta, requestedConfigId = "GEclient94", nowEpochSeconds = 999_999L),
        )
    }
}
```

- [ ] **Step 2: Запустить тест — убедиться, что падает**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests "*ProfileReuseTest*" --no-daemon`
Expected: FAIL — `Unresolved reference: ProfileMeta` (компиляция не проходит).

- [ ] **Step 3: Написать минимальную реализацию**

```kotlin
package com.impossi8le.vpnapp.domain.config

/**
 * Что установлено на устройстве и до каких пор это годно.
 *
 * Лежит рядом с профилем, а не на сервере: клиент не спрашивает «нет ли версии
 * новее», он решает по локальным данным, нужен ли вообще запрос.
 */
data class ProfileMeta(
    val configId: String,
    val version: String,
    val endDateEpochSeconds: Long,
)

/** Хранилище метаданных профиля. Реализация — рядом с профилем, в core:config. */
interface ProfileMetaStore {
    fun load(): ProfileMeta?
    fun save(meta: ProfileMeta)
    fun clear()
}

enum class ProfileUse { Missing, Expired, Reusable }

/**
 * Нужен ли запрос к серверу, чтобы получить профиль.
 *
 * Чистая функция: тот же набор входов даёт тот же ответ на любом запуске, поэтому
 * её проверяют тестом, а не сетью. Запрос нужен (`Missing`), когда профиля нет,
 * метаданных нет или запрошен другой конфиг. `Expired` — профиль недействителен
 * по сроку: его надо удалить. `Reusable` — можно поднимать из файла без сети.
 */
fun decideProfileUse(
    hasProfile: Boolean,
    meta: ProfileMeta?,
    requestedConfigId: String,
    nowEpochSeconds: Long,
): ProfileUse = when {
    !hasProfile -> ProfileUse.Missing
    meta == null -> ProfileUse.Missing
    meta.configId != requestedConfigId -> ProfileUse.Missing
    meta.endDateEpochSeconds <= nowEpochSeconds -> ProfileUse.Expired
    else -> ProfileUse.Reusable
}
```

- [ ] **Step 4: Запустить тест — убедиться, что проходит**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:domain:test --tests "*ProfileReuseTest*" --no-daemon`
Expected: PASS (5 тестов).

- [ ] **Step 5: Коммит**

```bash
git add android/core/domain/src/main/kotlin/com/impossi8le/vpnapp/domain/config/ProfileMeta.kt android/core/domain/src/test/kotlin/com/impossi8le/vpnapp/domain/config/ProfileReuseTest.kt
git commit -m "Decide locally whether the profile needs refetching"
```

---

### Task 2: `FileProfileMetaStore` — метаданные на диске

**Files:**
- Create: `android/core/config/src/main/kotlin/com/impossi8le/vpnapp/config/FileProfileMetaStore.kt`
- Test: `android/core/config/src/test/kotlin/com/impossi8le/vpnapp/config/FileProfileMetaStoreTest.kt`

**Interfaces:**
- Consumes: `ProfileMeta`, `ProfileMetaStore` (Task 1).
- Produces: `FileProfileMetaStore(dir: File, json: Json = Json { ignoreUnknownKeys = true })`, файл `dir/profile.meta`.

- [ ] **Step 1: Написать падающий тест**

```kotlin
package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ProfileMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileProfileMetaStoreTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `сохранённые метаданные читаются обратно`() {
        val store = FileProfileMetaStore(dir)
        val meta = ProfileMeta("GEclient94", "2026-10-02T12:45:56Z", 1_000_000L)

        store.save(meta)

        assertEquals(meta, store.load())
    }

    @Test
    fun `пустое хранилище возвращает null`() {
        assertNull(FileProfileMetaStore(dir).load())
    }

    @Test
    fun `повреждённый файл не роняет приложение`() {
        File(dir, "profile.meta").writeText("{ это не json")

        assertNull(FileProfileMetaStore(dir).load())
    }

    @Test
    fun `clear удаляет метаданные`() {
        val store = FileProfileMetaStore(dir)
        store.save(ProfileMeta("GEclient94", "v1", 1_000_000L))

        store.clear()

        assertNull(store.load())
    }
}
```

- [ ] **Step 2: Запустить тест — убедиться, что падает**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:config:test --tests "*FileProfileMetaStoreTest*" --no-daemon`
Expected: FAIL — `Unresolved reference: FileProfileMetaStore`.

- [ ] **Step 3: Написать минимальную реализацию**

```kotlin
package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ProfileMeta
import com.impossi8le.vpnapp.domain.config.ProfileMetaStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/**
 * Метаданные профиля рядом с самим профилем.
 *
 * Шифровать нечего: `configId`, версия и срок — не секреты, а приватный ключ
 * лежит в `.ovpn`, не здесь. Файл вплотную к профилю, чтобы очистка уносила оба
 * и они не расходились.
 *
 * `load` не бросает: повреждённый файл — это «метаданных нет», и клиент просто
 * сходит за профилем заново. Ронять экран из-за испорченного кэша нельзя.
 */
class FileProfileMetaStore(
    dir: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProfileMetaStore {

    private val file = File(dir, "profile.meta")

    override fun load(): ProfileMeta? = try {
        val root = json.parseToJsonElement(file.readText()).jsonObject
        val configId = root.str("config_id") ?: return null
        val version = root.str("version") ?: return null
        ProfileMeta(
            configId = configId,
            version = version,
            endDateEpochSeconds = root["end_date"]?.jsonPrimitive?.long ?: return null,
        )
    } catch (_: Exception) {
        null
    }

    override fun save(meta: ProfileMeta) {
        val payload = buildJsonObject {
            put("config_id", JsonPrimitive(meta.configId))
            put("version", JsonPrimitive(meta.version))
            put("end_date", JsonPrimitive(meta.endDateEpochSeconds))
        }
        file.writeText(payload.toString())
    }

    override fun clear() {
        if (file.exists()) file.delete()
    }

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.content
}
```

- [ ] **Step 4: Запустить тест — убедиться, что проходит**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:config:test --tests "*FileProfileMetaStoreTest*" --no-daemon`
Expected: PASS (4 теста).

- [ ] **Step 5: Коммит**

```bash
git add android/core/config/src/main/kotlin/com/impossi8le/vpnapp/config/FileProfileMetaStore.kt android/core/config/src/test/kotlin/com/impossi8le/vpnapp/config/FileProfileMetaStoreTest.kt
git commit -m "Store profile metadata next to the profile"
```

---

### Task 3: `ConfigManager` пишет метаданные при успешном применении

**Files:**
- Modify: `android/core/config/src/main/kotlin/com/impossi8le/vpnapp/config/ConfigManager.kt`
- Modify: `android/core/config/src/test/kotlin/com/impossi8le/vpnapp/config/ConfigManagerTest.kt`
- Create: `android/core/config/src/test/kotlin/com/impossi8le/vpnapp/config/TestFakes.kt`

**Interfaces:**
- Consumes: `ProfileMetaStore`, `ProfileMeta` (Task 1).
- Produces:
  - `ConfigManager(service: ConfigService, store: ProfileStore, meta: ProfileMetaStore)`
  - `suspend fun apply(configId: String, currentVersion: String?, endDateEpochSeconds: Long): ApplyResult`
  - `fun currentMeta(): ProfileMeta?`
  - `ApplyResult.Applied(val version: String)`, `ApplyResult.AlreadyCurrent(val version: String)`
  - `class FakeProfileMetaStore : ProfileMetaStore` (тестовый сорсет `core:config`)

Примечание: `test-support` намеренно НЕ подключается — он для кросс-модульных фейков (используется `core:network` и `feature:*`), а `core:config` до сих пор его не тянет и держит фейки локально. Сохраняем это.

- [ ] **Step 1: Создать фейк метаданных в тестовом сорсете `core:config`**

```kotlin
package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ProfileMeta
import com.impossi8le.vpnapp.domain.config.ProfileMetaStore

/** Метаданные в памяти. Для тестов, которым важен не файл, а факт записи. */
class FakeProfileMetaStore(initial: ProfileMeta? = null) : ProfileMetaStore {
    var value: ProfileMeta? = initial
        private set
    var saveCount: Int = 0
        private set

    override fun load(): ProfileMeta? = value

    override fun save(meta: ProfileMeta) {
        value = meta
        saveCount++
    }

    override fun clear() {
        value = null
    }
}
```

- [ ] **Step 2: Написать падающий тест и поправить существующие**

В `ConfigManagerTest` уже есть приватный `FakeConfigService(config, failure)` и хелперы `manager(...)`, `store()`, `expectApply(...)`. Нужно:

1. Добавить `meta` в `manager` и `endDate` в `expectApply`:

```kotlin
    private fun manager(
        config: FetchedConfig? = null,
        failure: Throwable? = null,
        meta: FakeProfileMetaStore = FakeProfileMetaStore(),
    ) = ConfigManager(FakeConfigService(config, failure), store(), meta)

    private fun expectApply(
        manager: ConfigManager,
        currentVersion: String?,
        expected: ApplyResult,
        endDate: Long = 1_000_000L,
    ) = runTest { assertEquals(expected, manager.apply("nl-ams-1", currentVersion, endDate)) }

    private fun applyOf(manager: ConfigManager): ApplyResult =
        kotlinx.coroutines.runBlocking { manager.apply("nl-ams-1", null, 1_000_000L) }
```

2. Поправить ожидания `Applied`/`AlreadyCurrent` на версию: строки 60, 68, 141, 147 → `ApplyResult.Applied("v1")`, `ApplyResult.AlreadyCurrent("v1")`. Тесты `Rejected`, `HashMismatch`, `FetchFailed` не меняются.
3. Тесты, создающие `ConfigManager(...)` напрямую (строки 79, 90, 91, 102, 103, 115), дополнить третьим аргументом `FakeProfileMetaStore()`.

Затем добавить тест:

```kotlin
    @Test
    fun `успешное применение записывает метаданные профиля`() {
        val metaStore = FakeProfileMetaStore()
        val fetched = FetchedConfig(raw = valid, version = "v7", hash = sha256(valid))
        val manager = ConfigManager(FakeConfigService(fetched), store(), metaStore)

        runTest { manager.apply("GEclient94", currentVersion = null, endDateEpochSeconds = 1_000_000L) }

        assertEquals(ProfileMeta("GEclient94", "v7", 1_000_000L), metaStore.load())
    }

    @Test
    fun `совпавшая версия всё равно фиксирует срок подписки`() {
        val metaStore = FakeProfileMetaStore()
        val fetched = FetchedConfig(raw = valid, version = "v1", hash = sha256(valid))
        val manager = ConfigManager(FakeConfigService(fetched), store(), metaStore)

        val result = kotlinx.coroutines.runBlocking {
            manager.apply("GEclient94", currentVersion = "v1", endDateEpochSeconds = 2_000_000L)
        }

        assertEquals(ApplyResult.AlreadyCurrent("v1"), result)
        assertEquals(ProfileMeta("GEclient94", "v1", 2_000_000L), metaStore.load())
    }
```

Нужны импорты в `ConfigManagerTest`: `com.impossi8le.vpnapp.domain.config.ProfileMeta`.

- [ ] **Step 3: Запустить тест — убедиться, что падает**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:config:test --tests "*ConfigManagerTest*" --no-daemon`
Expected: FAIL — слишком много аргументов для `ConfigManager` / `apply`.

- [ ] **Step 4: Изменить реализацию**

Заменить сигнатуры и тела в `ConfigManager`:

```kotlin
class ConfigManager(
    private val service: ConfigService,
    private val store: ProfileStore,
    private val meta: ProfileMetaStore,
) {

    suspend fun apply(
        configId: String,
        currentVersion: String?,
        endDateEpochSeconds: Long,
    ): ApplyResult {
        val result = service.fetchConfig(configId)
        if (result.isFailure) {
            val error = (result.exceptionOrNull() as? ConfigFetchException)?.error
                ?: ConfigFetchError.Unexpected(statusCode = 0)
            return ApplyResult.FetchFailed(error)
        }
        val fetched = result.getOrThrow()

        if (currentVersion != null && currentVersion == fetched.version) {
            // Профиль уже актуальный — но метаданные всё равно фиксируем: срок
            // подписки мог сдвинуться, и по нему решается «пора удалять».
            meta.save(ProfileMeta(configId, fetched.version, endDateEpochSeconds))
            return ApplyResult.AlreadyCurrent(fetched.version)
        }

        val staged = try {
            store.stage(fetched.raw)
        } catch (_: IllegalArgumentException) {
            return ApplyResult.Rejected
        }

        store.commit(staged)

        if (!hashMatches(fetched)) {
            store.rollback()
            return ApplyResult.HashMismatch
        }

        meta.save(ProfileMeta(configId, fetched.version, endDateEpochSeconds))
        return ApplyResult.Applied(fetched.version)
    }

    fun current(): Profile? = store.load()

    fun currentMeta(): ProfileMeta? = meta.load()

    // hashMatches и sha256Hex — без изменений
}
```

И обновить `ApplyResult`:

```kotlin
sealed interface ApplyResult {
    /** Записано и проверено. `version` — версия установленного профиля. */
    data class Applied(val version: String) : ApplyResult

    /** Версия совпала с текущей — запись пропущена. */
    data class AlreadyCurrent(val version: String) : ApplyResult

    data object Rejected : ApplyResult
    data object HashMismatch : ApplyResult
    data class FetchFailed(val error: ConfigFetchError) : ApplyResult
}
```

Добавить импорты в `ConfigManager.kt`: `com.impossi8le.vpnapp.domain.config.ProfileMeta`, `com.impossi8le.vpnapp.domain.config.ProfileMetaStore`.

Примечание: старые тесты `ConfigManagerTest`, ссылавшиеся на `ApplyResult.Applied` как объект, поправить на `ApplyResult.Applied(...)` с ожидаемой версией.

- [ ] **Step 5: Запустить тесты — убедиться, что проходят**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:config:test --no-daemon`
Expected: PASS (все тесты `core:config`, включая новые).

- [ ] **Step 6: Коммит**

```bash
git add android/core/config/src/main/kotlin/com/impossi8le/vpnapp/config/ConfigManager.kt android/core/config/src/test/kotlin/com/impossi8le/vpnapp/config/ConfigManagerTest.kt android/core/config/src/test/kotlin/com/impossi8le/vpnapp/config/TestFakes.kt
git commit -m "Record which config is installed and until when"
```

---

### Task 4: `ProfilePreparer` — решает, нужен ли запрос, и готовит профиль

**Files:**
- Create: `android/core/config/src/main/kotlin/com/impossi8le/vpnapp/config/ProfilePreparer.kt`
- Create: `android/core/config/src/test/kotlin/com/impossi8le/vpnapp/config/ScriptedConfigService.kt`
- Test: `android/core/config/src/test/kotlin/com/impossi8le/vpnapp/config/ProfilePreparerTest.kt`

**Interfaces:**
- Consumes: `ConfigService`, `ConfigList`, `ConfigSummary`, `SubscriptionStatus`, `ProfileStore`, `ProfileMetaStore`, `decideProfileUse`, `ConfigManager` (Task 3), `ConfigFetchError`.
- Produces: `class ProfilePreparer(service: ConfigService, manager: ConfigManager, store: ProfileStore, metaStore: ProfileMetaStore, now: () -> Long = { System.currentTimeMillis() / 1000 })`, `suspend fun ensureProfile(): PrepareResult`, `sealed interface PrepareResult { Ready; NoActiveConfig; SubscriptionExpired; Revoked; data class Failed(val reason: String) }`.

- [ ] **Step 1: Создать управляемый фейк `ConfigService` в тестовом сорсете**

Имя `ScriptedConfigService` (а не `FakeConfigService`) — чтобы не путать с приватным `FakeConfigService` внутри `ConfigManagerTest`.

```kotlin
package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ConfigList
import com.impossi8le.vpnapp.domain.config.ConfigService
import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.FetchedConfig
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus

/**
 * Сервис конфигов по сценарию. Параметризован ВХОДОМ (что вернуть), а не
 * выходом: иначе тест превратился бы в проверку самого фейка.
 */
class ScriptedConfigService(
    var listResult: Result<ConfigList> = Result.success(ConfigList(0L, emptyList())),
    var fetchResult: Result<FetchedConfig> = Result.failure(IllegalStateException("нет ответа")),
) : ConfigService {

    var listCount: Int = 0
        private set
    var fetchCount: Int = 0
        private set

    override suspend fun listConfigs(): Result<ConfigList> {
        listCount++
        return listResult
    }

    override suspend fun fetchConfig(configId: String): Result<FetchedConfig> {
        fetchCount++
        return fetchResult
    }
}

/** Одно активное подключение — частый вход для тестов подготовки профиля. */
fun activeConfig(
    id: String = "GEclient94",
    endDate: Long = 1_000_000L,
): ConfigSummary = ConfigSummary(
    id = id,
    name = "Германия · Франкфурт",
    countryCode = "DE",
    city = "Франкфурт",
    startDateEpochSeconds = 0L,
    endDateEpochSeconds = endDate,
    status = SubscriptionStatus.ACTIVE,
)
```

- [ ] **Step 2: Написать падающий тест**

```kotlin
package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ConfigList
import com.impossi8le.vpnapp.domain.config.FetchedConfig
import com.impossi8le.vpnapp.domain.config.ProfileMeta
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProfilePreparerTest {

    @TempDir
    lateinit var dir: File

    private val now = 999_999L

    private fun preparer(
        service: ScriptedConfigService,
        store: FileProfileStore,
        meta: FakeProfileMetaStore,
    ) = ProfilePreparer(
        service = service,
        manager = ConfigManager(service, store, meta),
        store = store,
        metaStore = meta,
        now = { now },
    )

    /** Профиль реально лежит на диске: валидатор — тот же, что у store() в ConfigManagerTest. */
    private fun storeWithProfile(): FileProfileStore {
        val s = FileProfileStore(dir) { raw -> String(raw).contains("client") }
        s.commit(s.stage("client\nremote host 1194\n".toByteArray()))
        return s
    }

    @Test
    fun `годный профиль — запрос не делается`() = runTest {
        val service = ScriptedConfigService(
            listResult = Result.success(ConfigList(0L, listOf(activeConfig()))),
        )
        val store = storeWithProfile()
        val meta = FakeProfileMetaStore(ProfileMeta("GEclient94", "v1", 1_000_000L))

        val result = preparer(service, store, meta).ensureProfile()

        assertEquals(PrepareResult.Ready, result)
        assertEquals(0, service.fetchCount) // профиль не скачивался
    }

    @Test
    fun `истёкший профиль удаляется и не поднимается`() = runTest {
        val service = ScriptedConfigService(
            listResult = Result.success(ConfigList(0L, listOf(activeConfig()))),
        )
        val store = storeWithProfile()
        val meta = FakeProfileMetaStore(ProfileMeta("GEclient94", "v1", 100L)) // срок в прошлом

        val result = preparer(service, store, meta).ensureProfile()

        assertEquals(PrepareResult.SubscriptionExpired, result)
        assertNull(store.load())   // профиль удалён
        assertNull(meta.load())    // и метаданные с ним
    }

    @Test
    fun `активного подключения нет`() = runTest {
        val service = ScriptedConfigService(
            listResult = Result.success(ConfigList(0L, emptyList())),
        )
        val result = preparer(service, FileProfileStore(dir), FakeProfileMetaStore()).ensureProfile()
        assertEquals(PrepareResult.NoActiveConfig, result)
    }

    @Test
    fun `профиля нет — скачивается и метаданные пишутся`() = runTest {
        val service = ScriptedConfigService(
            listResult = Result.success(ConfigList(0L, listOf(activeConfig()))),
            fetchResult = Result.success(FetchedConfig("client\nremote x\n".toByteArray(), "v7", "sha256:aa")),
        )
        val store = FileProfileStore(dir)
        val meta = FakeProfileMetaStore()

        val result = preparer(service, store, meta).ensureProfile()

        assertEquals(PrepareResult.Ready, result)
        assertEquals(1, service.fetchCount)
        assertEquals(ProfileMeta("GEclient94", "v7", 1_000_000L), meta.load())
    }
}
```

- [ ] **Step 3: Запустить тест — убедиться, что падает**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:config:test --tests "*ProfilePreparerTest*" --no-daemon`
Expected: FAIL — `Unresolved reference: ProfilePreparer`.

- [ ] **Step 4: Написать минимальную реализацию**

```kotlin
package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ConfigFetchError
import com.impossi8le.vpnapp.domain.config.ConfigService
import com.impossi8le.vpnapp.domain.config.ProfileMetaStore
import com.impossi8le.vpnapp.domain.config.ProfileStore
import com.impossi8le.vpnapp.domain.config.ProfileUse
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import com.impossi8le.vpnapp.domain.config.decideProfileUse

/** Итог подготовки профиля перед подключением. */
sealed interface PrepareResult {
    /** Профиль на диске и годен — можно поднимать туннель. */
    data object Ready : PrepareResult

    /** Ни одного действующего подключения. Не ошибка: пользователю нечего включать. */
    data object NoActiveConfig : PrepareResult

    /** Подписка кончилась: профиль удалён. */
    data object SubscriptionExpired : PrepareResult

    /** Доступ к конфигу отозван. */
    data object Revoked : PrepareResult

    /** Всё прочее: сеть, сессия, неожиданный ответ. */
    data class Failed(val reason: String) : PrepareResult
}

/**
 * Готовит профиль к подключению, по возможности НЕ обращаясь к серверу.
 *
 * Сначала смотрит на локальные метаданные: если профиль уже лежит и не истёк,
 * `GET /config/{id}` не делается вовсе. Сеть нужна, только когда профиля нет,
 * запрошен другой конфиг или срок вышел.
 *
 * `now` внедряется параметром ради детерминированного теста — та же причина,
 * что у `toRowState` в presentation.
 */
class ProfilePreparer(
    private val service: ConfigService,
    private val manager: ConfigManager,
    private val store: ProfileStore,
    private val metaStore: ProfileMetaStore,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    suspend fun ensureProfile(): PrepareResult {
        val list = service.listConfigs().getOrElse {
            return PrepareResult.Failed("не удалось получить список подключений")
        }

        val active = list.configs.firstOrNull { it.status == SubscriptionStatus.ACTIVE }
            ?: return PrepareResult.NoActiveConfig

        val hasProfile = store.load() != null
        val meta = metaStore.load()

        when (decideProfileUse(hasProfile, meta, active.id, now())) {
            ProfileUse.Reusable -> return PrepareResult.Ready
            ProfileUse.Expired -> {
                // Профиль перестал работать по сроку — держать его незачем.
                store.clear()
                metaStore.clear()
                return PrepareResult.SubscriptionExpired
            }
            ProfileUse.Missing -> Unit
        }

        return when (val applied = manager.apply(active.id, meta?.version, active.endDateEpochSeconds)) {
            is ApplyResult.Applied, is ApplyResult.AlreadyCurrent -> PrepareResult.Ready
            ApplyResult.Rejected, ApplyResult.HashMismatch -> PrepareResult.Failed("конфиг отклонён")
            is ApplyResult.FetchFailed -> when (applied.error) {
                ConfigFetchError.SubscriptionExpired -> {
                    store.clear()
                    metaStore.clear()
                    PrepareResult.SubscriptionExpired
                }
                ConfigFetchError.ConfigRevoked -> PrepareResult.Revoked
                ConfigFetchError.Unauthorized -> PrepareResult.Failed("нужен повторный вход")
                ConfigFetchError.NetworkUnavailable -> PrepareResult.Failed("нет сети")
                else -> PrepareResult.Failed("не удалось получить конфиг")
            }
        }
    }
}
```

- [ ] **Step 5: Запустить тесты — убедиться, что проходят**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :core:config:test --no-daemon`
Expected: PASS (все тесты `core:config`).

- [ ] **Step 6: Коммит**

```bash
git add android/core/config/src/main/kotlin/com/impossi8le/vpnapp/config/ProfilePreparer.kt android/core/config/src/test/kotlin/com/impossi8le/vpnapp/config/ProfilePreparerTest.kt android/core/config/src/test/kotlin/com/impossi8le/vpnapp/config/ScriptedConfigService.kt
git commit -m "Prepare the profile without asking the server when it is still valid"
```

---

### Task 5: `Backend.kt` и `AppGraph` — точка сборки

**Files:**
- Create: `android/app/src/main/kotlin/com/impossi8le/vpnapp/Backend.kt`
- Create: `android/app/src/main/kotlin/com/impossi8le/vpnapp/AppGraph.kt`

**Interfaces:**
- Consumes: `ApiClient`, `AuthApi`, `ConfigApi`, `AndroidSecureBackend`, `SessionStoreImpl`, `FileProfileStore`, `FileProfileMetaStore`, `ConfigManager`, `ProfilePreparer`, `TunnelControlling`, `SessionStore`, `ProfileStore`, `ProfileMetaStore`.
- Produces: `const val API_BASE_URL: String`; `class AppGraph(context: Context, tunnel: TunnelControlling)` с полями `apiClient`, `authApi`, `configApi`, `sessionStore`, `profileStore`, `metaStore`, `configManager`, `preparer`; методами `fun restoreSession(): Boolean`, `suspend fun signOut()`.

- [ ] **Step 1: Написать `Backend.kt`**

```kotlin
package com.impossi8le.vpnapp

/**
 * Адрес бэкенда — в одном месте.
 *
 * Домена у сервиса нет: хост `194-87-252-181.sslip.io` — публичный DNS,
 * отдающий IP прямо в имени. Пиновать сам IP нельзя: при переезде сервера он
 * поменяется, а строка sslip.io останется. TLS включён, цепочка Let's Encrypt
 * валидна — отключать проверку не нужно и нельзя.
 */
const val API_BASE_URL = "https://194-87-252-181.sslip.io:4443/api/v1"
```

- [ ] **Step 2: Написать `AppGraph.kt`**

```kotlin
package com.impossi8le.vpnapp

import android.content.Context
import com.impossi8le.vpnapp.config.ConfigManager
import com.impossi8le.vpnapp.config.FileProfileMetaStore
import com.impossi8le.vpnapp.config.FileProfileStore
import com.impossi8le.vpnapp.config.ProfilePreparer
import com.impossi8le.vpnapp.domain.auth.SessionStore
import com.impossi8le.vpnapp.domain.config.ProfileMetaStore
import com.impossi8le.vpnapp.domain.config.ProfileStore
import com.impossi8le.vpnapp.domain.tunnel.TunnelControlling
import com.impossi8le.vpnapp.network.ApiClient
import com.impossi8le.vpnapp.network.AuthApi
import com.impossi8le.vpnapp.network.ConfigApi
import com.impossi8le.vpnapp.security.AndroidSecureBackend
import com.impossi8le.vpnapp.security.SessionStoreImpl
import java.io.File

/**
 * Точка сборки: сеть, хранилища и координаторы в одном месте.
 *
 * DI-библиотеку не тянем осознанно: модулей с состоянием мало, а граф в двадцать
 * строк виден целиком. Библиотека принесла бы генерацию и правила, которых нечем
 * оправдать.
 *
 * **Границы §6.** Здесь нет и не может быть `ProtectionVerdict`: зелёный статус
 * собирается единственным конструктором в `core:domain` из трёх измеренных
 * фактов. Ни один метод этого класса не способен его породить.
 */
class AppGraph(
    context: Context,
    private val tunnel: TunnelControlling,
) {
    private val filesDir: File = context.applicationContext.filesDir

    val apiClient = ApiClient(baseUrl = API_BASE_URL)
    val authApi = AuthApi(apiClient)
    val configApi = ConfigApi(apiClient)

    val sessionStore: SessionStore = SessionStoreImpl(AndroidSecureBackend(context))
    val profileStore: ProfileStore = FileProfileStore(filesDir)
    val metaStore: ProfileMetaStore = FileProfileMetaStore(filesDir)

    val configManager = ConfigManager(configApi, profileStore, metaStore)
    val preparer = ProfilePreparer(configApi, configManager, profileStore, metaStore)

    /**
     * Восстановить сессию при запуске. `true` — токен жив, вход не нужен.
     *
     * Истёкшую сессию чистим сразу: держать протухший токен — значит получить
     * `401` на первом же запросе и разбираться с ним на экране вместо входа.
     */
    fun restoreSession(): Boolean {
        val session = sessionStore.load() ?: return false
        if (session.expiresAtEpochSeconds <= System.currentTimeMillis() / 1000) {
            sessionStore.clear()
            return false
        }
        apiClient.sessionToken = session.token
        return true
    }

    /**
     * Полный выход. Порядок обязателен и повторяет `AccountViewModel`:
     * туннель → профиль с метаданными → сессия → токен в памяти.
     *
     * Ядро не должно держать удаляемый профиль; сессия не должна исчезнуть
     * раньше поднятого соединения. Профиль уходит вместе с сессией — иначе
     * следующая сессия подхватила бы чужой профиль.
     */
    suspend fun signOut() {
        tunnel.disconnect()
        profileStore.clear()
        metaStore.clear()
        sessionStore.clear()
        apiClient.sessionToken = null
    }
}
```

- [ ] **Step 3: Собрать приложение — убедиться, что компилируется**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL. (Классы ещё нигде не используются — это задел для следующих задач; сам факт сборки проверяет корректность сигнатур.)

- [ ] **Step 4: Коммит**

```bash
git add android/app/src/main/kotlin/com/impossi8le/vpnapp/Backend.kt android/app/src/main/kotlin/com/impossi8le/vpnapp/AppGraph.kt
git commit -m "Add the composition root that talks to the real backend"
```

---

### Task 6: Вход — `AuthViewModel` инициализируется и делает реальный вход

**Files:**
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt`

**Interfaces:**
- Consumes: `AppGraph` (Task 5), `AuthViewModel(service, sessionStore, deviceName)`, `AuthUiState`.
- Produces: в `VpnApp()` — `authViewModel`; наблюдение `AuthUiState.SignedIn(token)` устанавливает `graph.apiClient.sessionToken` и ведёт переход на `Connection`.

- [ ] **Step 1: Связать `AuthViewModel` с графом**

В `VpnApp()` добавить (рядом с `viewModel` для `HomeViewModel`):

```kotlin
    val graph = remember { AppGraph(context.applicationContext, tunnel) }

    val authViewModel: AuthViewModel = viewModel {
        // Build.MODEL идёт в device_name (закрывает расхождение №3 аудита):
        // поддержка получает модель телефона, а не пустоту.
        AuthViewModel(graph.authApi, graph.sessionStore, android.os.Build.MODEL)
    }
    val authState by authViewModel.state.collectAsState()
```

- [ ] **Step 2: Обработать `StartLogin` и открыть Telegram**

В `onIntent` заменить ветку `else -> Unit` и добавить обработку входа:

```kotlin
                AppIntent.StartLogin -> {
                    authViewModel.startLogin()
                    go(/* LoginWaiting */) // переход уже делает AppRoot по onLogin
                }
```
Уточнение: переход на `LoginWaiting` выполняет `AppRoot` (см. `LoginScreen.onLogin` → `go(AppDestination.LoginWaiting)`), поэтому в `StartLogin` достаточно `authViewModel.startLogin()`.

Открытие Telegram — по состоянию `AwaitingNonce`:

```kotlin
    // Открываем Telegram, когда пришла ссылка. Показывать экран ожидания без
    // попытки открыть бота — значит оставить пользователя гадать, что дальше.
    LaunchedEffect(authState) {
        val challenge = (authState as? AuthUiState.AwaitingNonce)?.challenge ?: return@LaunchedEffect
        val opened = runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(challenge.deepLink),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!opened) { /* Telegram не установлен: экран ожидания покажет кнопку «Открыть Telegram ещё раз» */ }
    }
```

Добавить импорты: `androidx.compose.runtime.LaunchedEffect`, `com.impossi8le.vpnapp.feature.auth.AuthViewModel`, `com.impossi8le.vpnapp.feature.auth.AuthUiState`.

- [ ] **Step 3: Перевести `SignedIn` в переход и токен**

Добавить после блока выше:

```kotlin
    // Сессия получена: токен кладём в общий ApiClient (его читают ConfigApi и
    // профиль), затем идём на главный экран. Возврат на вход бессмысленен —
    // историю сбрасывает AppRoot.resetTo внутри перехода.
    LaunchedEffect(authState) {
        val signedIn = authState as? AuthUiState.SignedIn ?: return@LaunchedEffect
        graph.apiClient.sessionToken = signedIn.token
    }
```

Переход на `Connection` после `SignedIn` обеспечивает `AppRoot`: `LoginWaitingScreen` вызывает `onContinueDemo` → `resetTo(Connection)`. В этой задаче демо-путь ещё жив (его гейтим в Task 11); переход по реальному входу произойдёт, когда `LoginWaitingScreen` получит признак «вход состоялся» — это делает Task 7.

- [ ] **Step 4: Собрать — убедиться, что компилируется**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Коммит**

```bash
git add android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt
git commit -m "Start a real sign-in from the login screen"
```

---

### Task 7: Поле ввода кода из бота на экране ожидания

**Files:**
- Modify: `android/feature/auth/src/main/kotlin/com/impossi8le/vpnapp/feature/auth/LoginWaitingScreen.kt`
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/AppRoot.kt`
- Test: `android/feature/auth/src/androidTest/kotlin/com/impossi8le/vpnapp/feature/auth/LoginWaitingScreenTest.kt`

**Interfaces:**
- Consumes: `AuthViewModel.submitNonce(nonce: String)`, `AuthUiState`.
- Produces: `LoginWaitingScreen(remainingLabel, signingIn: Boolean, errorText: String?, onSubmitNonce: (String) -> Unit, onReopenTelegram, onContinueDemo, ...)`; тег `LOGIN_WAITING_NONCE_TAG`, `LOGIN_WAITING_SUBMIT_TAG`.

- [ ] **Step 1: Написать падающий инструментальный тест**

```kotlin
package com.impossi8le.vpnapp.feature.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LoginWaitingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nonceFieldIsShownAndSubmits() {
        var submitted = ""
        compose.setContent {
            LoginWaitingScreen(
                remainingLabel = "4:59",
                signingIn = false,
                errorText = null,
                onSubmitNonce = { submitted = it },
                onReopenTelegram = {},
                onContinueDemo = {},
            )
        }

        compose.onNodeWithTag(LOGIN_WAITING_NONCE_TAG).performTextInput("4821")
        compose.onNodeWithTag(LOGIN_WAITING_SUBMIT_TAG).performClick()

        assertTrue(submitted == "4821")
    }
}
```
(Имя метода — camelCase: в DEX backtick-имена запрещены.)

- [ ] **Step 2: Запустить — убедиться, что падает**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :feature:auth:connectedDebugAndroidTest --no-daemon` (нужен запущенный эмулятор: `scripts/run-emulator.sh`)
Expected: FAIL — нет параметров `signingIn`, `errorText`, `onSubmitNonce` и тегов.

- [ ] **Step 3: Изменить `LoginWaitingScreen`**

Добавить теги:

```kotlin
const val LOGIN_WAITING_NONCE_TAG = "login_waiting_nonce"
const val LOGIN_WAITING_SUBMIT_TAG = "login_waiting_submit"
```

Расширить сигнатуру и добавить блок ввода после блока с отсчётом:

```kotlin
@Composable
fun LoginWaitingScreen(
    remainingLabel: String,
    signingIn: Boolean,
    errorText: String?,
    onSubmitNonce: (String) -> Unit,
    onReopenTelegram: () -> Unit,
    onContinueDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var nonce by remember { mutableStateOf("") }
    // ... существующее содержимое до блока с отсчётом ...
```

Текст-инструкцию поправить: «Откройте Telegram — бот пришлёт код подтверждения. Введите его здесь.» Затем:

```kotlin
        OutlinedTextField(
            value = nonce,
            onValueChange = { nonce = it.filter(Char::isDigit).take(6) },
            singleLine = true,
            label = { Text("Код из бота") },
            isError = errorText != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
                .testTag(LOGIN_WAITING_NONCE_TAG),
        )

        if (errorText != null) {
            Text(
                text = errorText,
                color = VpnColors.Amber,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        GhostButton(
            text = if (signingIn) "Проверяем…" else "Подтвердить",
            onClick = { onSubmitNonce(nonce) },
            height = 46.dp,
            testTag = LOGIN_WAITING_SUBMIT_TAG,
            modifier = Modifier.padding(top = 12.dp),
        )
```

Добавить импорты: `androidx.compose.material3.OutlinedTextField`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.getValue`, `androidx.compose.runtime.setValue`.

- [ ] **Step 4: Связать с `AuthViewModel` в `AppRoot`/`MainActivity`**

`AppRoot` получает новые поля состояния: `AppRootState.signingIn: Boolean = false`, `AppRootState.loginError: String? = null`. В `AppDestination.LoginWaiting`:

```kotlin
            AppDestination.LoginWaiting -> LoginWaitingScreen(
                remainingLabel = state.loginRemainingLabel,
                signingIn = state.signingIn,
                errorText = state.loginError,
                onSubmitNonce = { nonce -> onIntent(AppIntent.SubmitNonce(nonce)) },
                onReopenTelegram = { onIntent(AppIntent.StartLogin) },
                onContinueDemo = {
                    stack.resetTo(AppDestination.Connection)
                    destination = stack.current()
                },
            )
```
Добавить в `AppIntent`: `data class SubmitNonce(val nonce: String) : AppIntent`.

Переход по успешному входу: когда `authState` становится `SignedIn`, `MainActivity` ведёт на `Connection`. Так как навигация живёт в `AppRoot`, добавить в `AppRootState` поле `signedIn: Boolean = false`, и в `AppRoot`:

```kotlin
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            stack.resetTo(AppDestination.Connection)
            destination = stack.current()
        }
    }
```

В `MainActivity`:
```kotlin
                AppIntent.SubmitNonce -> authViewModel.submitNonce(intent.nonce)
```
и при сборке `AppRootState` передать из `authState`:
```kotlin
            signingIn = authState is AuthUiState.Polling,
            loginError = (authState as? AuthUiState.Failed)?.reason,
            signedIn = authState is AuthUiState.SignedIn,
```

- [ ] **Step 5: Запустить тесты — убедиться, что проходят**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :feature:auth:connectedDebugAndroidTest --no-daemon`
Expected: PASS.

- [ ] **Step 6: Коммит**

```bash
git add android/feature/auth/src/main/kotlin/com/impossi8le/vpnapp/feature/auth/LoginWaitingScreen.kt android/feature/auth/src/androidTest/kotlin/com/impossi8le/vpnapp/feature/auth/LoginWaitingScreenTest.kt android/app/src/main/kotlin/com/impossi8le/vpnapp/AppRoot.kt android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt
git commit -m "Ask for the bot code on the waiting screen"
```

---

### Task 8: Живой `/me` вместо `MockApi`

**Files:**
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt`

**Interfaces:**
- Consumes: `AppGraph.configApi` (Task 5), `ConfigApi.listConfigs()`, `toRowStates`.
- Produces: `AppRootState.configs` наполняется из `graph.configApi`; демо-ответ `DemoMode.api` больше не используется в основном пути.

- [ ] **Step 1: Заменить источник списка**

В `VpnApp()` заменить `produceState`, читающий `DemoMode.api`, на реальный:

```kotlin
    // Список подключений — из живого /me. Обновляется при появлении экрана
    // подключения и по кнопке; здесь — первичная загрузка. Токен уже в
    // apiClient (Task 6), иначе сервер ответит 401 и мы покажем «войдите».
    val configs by produceState(initialValue = emptyList(), graph.apiClient.sessionToken) {
        value = graph.configApi.listConfigs().getOrNull()
            ?.configs
            ?.toRowStates(System.currentTimeMillis() / 1000)
            .orEmpty()
    }
```

- [ ] **Step 2: Собрать и установить на эмулятор**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:assembleDebug --no-daemon && bash ../../../scripts/install-apk.sh` (из каталога android путь к скрипту зависит от раскладки — использовать существующий `scripts/install-apk.sh`).
Expected: сборка и установка без ошибок.

- [ ] **Step 3: Проверить, что список приходит с сервера**

Установить APK, войти (или восстановить сессию), открыть главный экран. Ожидание: секция «Подключения» показывает реальные подключения из `/me`, а не три стуба `MockApi`. Если `configs` пуст — проверить в logcat, не вернул ли `/me` `401`.

- [ ] **Step 4: Коммит**

```bash
git add android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt
git commit -m "Load the connection list from the live backend"
```

---

### Task 9: Подключение — сначала профиль, потом туннель

**Files:**
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt`
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/AppRoot.kt` (поля состояния для отказа)

**Interfaces:**
- Consumes: `AppGraph.preparer` (Task 5), `ProfilePreparer.ensureProfile()`, `PrepareResult` (Task 4), `HomeViewModel.connect()`.
- Produces: `AppRootState.prepareError: String?`; ветка `StatusAction.Connect` вызывает `preparer.ensureProfile()` и по результату либо `viewModel.connect()`, либо выставляет `prepareError`.

- [ ] **Step 1: Добавить поле состояния**

В `AppRootState` добавить:

```kotlin
    /** Почему подключение не началось: нет подписки, отозван доступ, нет сети. */
    val prepareError: String? = null,
```

- [ ] **Step 2: Реализовать ветку `Connect`**

В `MainActivity`, в `onIntent`:

```kotlin
                        StatusAction.Connect -> {
                            // Сначала профиль, потом туннель. Если профиль уже
                            // лежит и годен, ensureProfile НЕ обращается к сети —
                            // поднимаем из файла (см. ProfilePreparer).
                            when (val prepared = graph.preparer.ensureProfile()) {
                                PrepareResult.Ready -> viewModel.connect()
                                PrepareResult.NoActiveConfig -> prepareMessage.value =
                                    "Нет активных подключений"
                                PrepareResult.SubscriptionExpired -> prepareMessage.value =
                                    "Подписка истекла"
                                PrepareResult.Revoked -> prepareMessage.value =
                                    "Доступ к подключению отозван"
                                is PrepareResult.Failed -> prepareMessage.value = prepared.reason
                            }
                        }
```
Где `prepareMessage` — `remember { mutableStateOf<String?>(null) }` в `VpnApp()`, передаётся в `AppRootState.prepareError = prepareMessage.value`. Добавить импорты `com.impossi8le.vpnapp.config.PrepareResult`.

- [ ] **Step 3: Показать причину отказа**

В `AppRoot`, в `AppDestination.Connection`, передать в `ConnectionScreen` предупреждение:

```kotlin
            AppDestination.Connection -> ConnectionScreen(
                // ... существующие параметры ...
                switchingWarning = state.switchingInProgress || state.prepareError != null,
                startProgressText = state.prepareError ?: state.startProgressText,
```
(Если `switchingWarning: Boolean` уже занят смыслом «переключение страны» — вместо заимствования добавить в `ConnectionScreen` отдельный параметр `notice: String?` и показать его тем же блоком, что и предупреждение. Выбрать вариант, который не искажает смысл существующего поля.)

- [ ] **Step 4: Собрать и проверить на эмуляторе**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL.

Установить, войти, нажать «Подключить». Ожидание: `profile.ovpn` появляется в `/data/data/com.impossi8le.vpnapp/files/`, туннель поднимается, экран честно показывает «защита не подтверждена» (замер не проводился).

- [ ] **Step 5: Коммит**

```bash
git add android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt android/app/src/main/kotlin/com/impossi8le/vpnapp/AppRoot.kt android/feature/home/src/main/kotlin/com/impossi8le/vpnapp/feature/home/ConnectionScreen.kt
git commit -m "Fetch the profile before raising the tunnel"
```

---

### Task 10: Выход гасит туннель, профиль и сессию

**Files:**
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt`

**Interfaces:**
- Consumes: `AppGraph.signOut()` (Task 5).
- Produces: в `onIntent` ветка `AppIntent.SignOut` вызывает `graph.signOut()` и ведёт на `Login`.

- [ ] **Step 1: Реализовать ветку `SignOut`**

```kotlin
                AppIntent.SignOut -> scope.launch {
                    // Порядок внутри signOut: туннель → профиль → сессия → токен.
                    graph.signOut()
                    authViewModel.signOut()
                }
```
Переход на экран входа после выхода: выставить `signedOut` в состоянии. Добавить в `AppRootState` `signedOut: Boolean = false`, в `AppRoot`:

```kotlin
    LaunchedEffect(state.signedOut) {
        if (state.signedOut) {
            stack.resetTo(AppDestination.Login)
            destination = stack.current()
        }
    }
```
В `MainActivity` выставлять `signedOut = authState is AuthUiState.Idle` при монтировании после выхода (флаг `remember { mutableStateOf(false) }`, ставится в `true` в ветке `SignOut` и передаётся в состояние).

- [ ] **Step 2: Проверить на эмуляторе**

Установить, войти, подключиться, выйти. Ожидание: туннель опущен, `files/profile.ovpn` и `files/profile.meta` удалены, экран вернулся на вход; повторный «Подключить» без входа недоступен (экран входа).

Проверка файлов:
```bash
MSYS_NO_PATHCONV=1 adb shell run-as com.impossi8le.vpnapp ls files 2>/dev/null
```
Expected: ни `profile.ovpn`, ни `profile.meta`.

- [ ] **Step 3: Коммит**

```bash
git add android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt android/app/src/main/kotlin/com/impossi8le/vpnapp/AppRoot.kt
git commit -m "Sign out stops the tunnel and wipes the profile and session"
```

---

### Task 11: Демонстрационная кнопка — только в debug

**Files:**
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/kotlin/com/impossi8le/vpnapp/AppRoot.kt`

**Interfaces:**
- Consumes: `BuildConfig.DEBUG`.
- Produces: `AppRootState.showDemoButton: Boolean`; кнопка «Демонстрация…» показывается только при `showDemoButton`.

- [ ] **Step 1: Включить `BuildConfig` в `:app`**

В `android { }` блока `:app/build.gradle.kts` добавить:

```kotlin
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG нужен, чтобы показать демонстрационный проход
        // ТОЛЬКО в debug-сборке. В release кнопки быть не должно.
        buildConfig = true
    }
```

- [ ] **Step 2: Гейтить кнопку**

В `AppRootState` добавить `showDemoButton: Boolean = false`. В `AppRootDestination.LoginWaiting` передать в `LoginWaitingScreen`:

```kotlin
                onSubmitNonce = { nonce -> onIntent(AppIntent.SubmitNonce(nonce)) },
                onReopenTelegram = { onIntent(AppIntent.StartLogin) },
                showDemoButton = state.showDemoButton,
                onContinueDemo = { ... },
```
В `LoginWaitingScreen` обернуть демонстрационную кнопку:

```kotlin
        if (showDemoButton) {
            GhostButton(
                text = "Демонстрация: показать экраны дальше",
                onClick = onContinueDemo,
                height = 46.dp,
                testTag = LOGIN_WAITING_DEMO_TAG,
                modifier = Modifier.padding(top = 9.dp),
            )
        }
```
В `MainActivity` передать `showDemoButton = BuildConfig.DEBUG`.

- [ ] **Step 3: Проверить, что в release кнопки нет**

Run: `cd /d/VPN_app/android && JAVA_HOME="D:/VPN_app/.tools/jdk17" ./gradlew :app:assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL; в debug-сборке кнопка на экране ожидания присутствует (проверить визуально на эмуляторе). Release-подпись локально не настраивается (§10.3) — факт отсутствия кнопки в release проверяется чтением кода (`BuildConfig.DEBUG == false` в release), а не сборкой.

- [ ] **Step 4: Коммит**

```bash
git add android/app/build.gradle.kts android/app/src/main/kotlin/com/impossi8le/vpnapp/AppRoot.kt android/app/src/main/kotlin/com/impossi8le/vpnapp/MainActivity.kt android/feature/auth/src/main/kotlin/com/impossi8le/vpnapp/feature/auth/LoginWaitingScreen.kt
git commit -m "Keep the demo shortcut out of release builds"
```

---

### Task 12: Сквозная проверка на эмуляторе и запись результата

**Files:**
- Create: `docs/testing/2026-10-05-api-wiring-live.md`

- [ ] **Step 1: Прогнать весь путь вручную**

1. Собрать и установить: `:app:assembleDebug` + `scripts/install-apk.sh`.
2. Запустить эмулятор (`scripts/run-emulator.sh`), приложение.
3. Войти: нажать «Войти тем же Telegram»; бот покажет код; ввести его; убедиться, что приложение ушло на главный экран.
4. Проверить список подключений: реальные записи из `/me`.
5. Нажать «Подключить»; убедиться, что `profile.ovpn` появился и туннель поднялся (`adb logcat -s VpnTunnel` печатает `CONNECTED`).
6. Выйти; убедиться, что профиль и сессия удалены.

- [ ] **Step 2: Записать результат**

Создать `docs/testing/2026-10-05-api-wiring-live.md`:

```markdown
# Сквозная проверка API на устройстве

Дата: 2026-10-05. Бэкенд: `https://194-87-252-181.sslip.io:4443/api/v1`.

| Шаг | Результат |
|---|---|
| Вход через бота | [факт] |
| Список из `/me` | [факт] |
| Загрузка профиля | [факт] |
| Поднятие туннеля | [факт] |
| Выход очищает файлы | [факт] |

Наблюдения: [что не сработало, что удивило].
Скриншоты: [пути].
```
Заполнить фактическими наблюдениями. Если что-то не сработало — записать как есть, не подгонять.

- [ ] **Step 3: Коммит**

```bash
git add docs/testing/2026-10-05-api-wiring-live.md
git commit -m "Record the live API verification on device"
```

---

## Self-Review

**Покрытие спеки:**
- §3.1 адрес — Task 5. §3.2 AppGraph/§3.3 сессия — Task 5 (+ restoreSession). §4 вход — Tasks 6, 7. §5 список — Task 8. §6.1 мета — Tasks 1, 2. §6.2 «когда трогаем сеть» — Tasks 1, 4. §6.3 удаление по сроку — Task 4. §6.4 подключение — Task 9. §6.5 выход — Task 10. §7 демо — Task 11. §8 проверка — Task 12. ≡ всё покрыто.

**Плейсхолдеры:** в Task 12 шаг 2 содержит `[факт]` намеренно — это шаблон отчёта, заполняемый наблюдаемыми фактами, а не план-заглушка.

**Согласованность типов:** `ProfileMeta(configId, version, endDateEpochSeconds)` — Task 1, используется в 2, 3, 4. `ApplyResult.Applied(version)`/`AlreadyCurrent(version)` — введены в Task 3, используются в Task 4. `PrepareResult` — Task 4, используется в Task 9. `AppGraph` поля — Task 5, используются в 6, 8, 9, 10. `ProfilePreparer(service, manager, store, metaStore, now)` — Task 4.

**Зависимости модулей:** `test-support` намеренно не задействован — он для кросс-модульных фейков (`core:network`, `feature:*`). `FakeProfileMetaStore` и `ScriptedConfigService` живут в `core/config/src/test`, `FakeConfigService` внутри `ConfigManagerTest` не переименовывается (приватный, конфликта нет благодаря имени `ScriptedConfigService`).

**Правки после самопроверки:** тесты Task 3 переписаны под фактические хелперы `ConfigManagerTest` (`manager(...)`, `store()`, `expectApply(...)`, приватный `FakeConfigService(config, failure)`); источник фейков перенесён из `test-support` в тестовый сорсет `core:config`; строки коммитов приведены в соответствие с путями.
