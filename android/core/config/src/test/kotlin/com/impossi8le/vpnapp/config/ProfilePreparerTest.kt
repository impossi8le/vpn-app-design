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
import java.security.MessageDigest

class ProfilePreparerTest {

    @TempDir
    lateinit var dir: File

    private val now = 999_999L

    /** `sha256:<hex>` — тот же формат, что сверяет ConfigManager после записи. */
    private fun sha256(bytes: ByteArray) =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
        assertEquals(0, service.listCount)  // и список подключений не запрашивался
    }

    @Test
    fun `годный профиль на диске — GET me не делается, даже когда сеть вернула бы иное`() = runTest {
        // listResult оставлен дефолтным (пустой список). Оффлайн-пользователь с
        // валидным профилем не должен зависеть от сети: если бы listConfigs()
        // всё-таки вызвали, активного подключения не нашлось бы и вернулся бы
        // NoActiveConfig (а при ошибке сети — Failed), но никак не Ready.
        val service = ScriptedConfigService()
        val store = storeWithProfile()
        val meta = FakeProfileMetaStore(ProfileMeta("GEclient94", "v1", 1_000_000L))

        val result = preparer(service, store, meta).ensureProfile()

        assertEquals(PrepareResult.Ready, result)
        assertEquals(0, service.listCount) // GET /me не сделан вовсе
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
    fun `смена конфига при совпадении версии не оставляет старые байты`() = runTest {
        // Профиля на диске нет, но метаданные — от ДРУГОГО конфига (A) с версией v1.
        // Активный теперь B, а сервер отдаёт B с ТОЙ ЖЕ версией v1 — коллизия.
        // Если версию A передать в apply, ConfigManager посчитает B уже
        // установленным (AlreadyCurrent) и не запишет его байты: профиля на диске
        // не останется вовсе. Гард `takeIf { it.configId == active.id }` это ловит:
        // storedVersion пуст, поэтому B записывается по-настоящему.
        // (Сценарий «профиль A уже на диске» теперь короче: валидный кэш отдаётся
        // без сети — это проверяют тесты переиспользования выше.)
        val bBytes = "client\nremote host 443\n".toByteArray()
        val service = ScriptedConfigService(
            listResult = Result.success(ConfigList(0L, listOf(activeConfig(id = "B")))),
            fetchResult = Result.success(FetchedConfig(bBytes, "v1", sha256(bBytes))),
        )
        val store = FileProfileStore(dir) // на диске профиля нет
        val meta = FakeProfileMetaStore(ProfileMeta("A", "v1", 1_000_000L))

        val result = preparer(service, store, meta).ensureProfile()

        assertEquals(PrepareResult.Ready, result)
        assertEquals(
            String(bBytes),
            String(store.load()!!.raw), // именно байты B
        )
    }

    @Test
    fun `профиля нет — скачивается и метаданные пишутся`() = runTest {
        val raw = "client\nremote x\n".toByteArray()
        val service = ScriptedConfigService(
            listResult = Result.success(ConfigList(0L, listOf(activeConfig()))),
            fetchResult = Result.success(FetchedConfig(raw, "v7", sha256(raw))),
        )
        val store = FileProfileStore(dir)
        val meta = FakeProfileMetaStore()

        val result = preparer(service, store, meta).ensureProfile()

        assertEquals(PrepareResult.Ready, result)
        assertEquals(1, service.fetchCount)
        assertEquals(ProfileMeta("GEclient94", "v7", 1_000_000L), meta.load())
    }

    @Test
    fun `смена страны — на диске профиль A, просят B, скачивается именно B`() = runTest {
        // Ключевой сценарий переключения: пользователь выбрал другое подключение,
        // а на диске лежит профиль прежнего. Гард быстрого пути не должен отдать
        // Ready по чужому кэшу — обязан сходить за B и записать ЕГО байты.
        val bBytes = "client\nremote host 25000\n".toByteArray()
        val service = ScriptedConfigService(
            listResult = Result.success(ConfigList(0L, listOf(activeConfig(id = "B")))),
            fetchResult = Result.success(FetchedConfig(bBytes, "v2", sha256(bBytes))),
        )
        val store = storeWithProfile() // на диске профиль A
        val meta = FakeProfileMetaStore(ProfileMeta("A", "v1", 1_000_000L))

        val result = preparer(service, store, meta).ensureProfile("B")

        assertEquals(PrepareResult.Ready, result)
        assertEquals(1, service.fetchCount)
        assertEquals(String(bBytes), String(store.load()!!.raw)) // именно байты B
        assertEquals(ProfileMeta("B", "v2", 1_000_000L), meta.load())
    }

    @Test
    fun `просят уже установленный конфиг — сети нет`() = runTest {
        // Оффлайн-гарантия сохраняется и для явного id: тот же конфиг на диске и
        // годен — GET /me и /config не делаются вовсе.
        val service = ScriptedConfigService()
        val store = storeWithProfile()
        val meta = FakeProfileMetaStore(ProfileMeta("A", "v1", 1_000_000L))

        val result = preparer(service, store, meta).ensureProfile("A")

        assertEquals(PrepareResult.Ready, result)
        assertEquals(0, service.fetchCount)
        assertEquals(0, service.listCount)
    }

    @Test
    fun `запрошенного конфига нет среди активных — не подставляем первый`() = runTest {
        // Просят Z, активен только A. Молчаливая подстановка A переключила бы не
        // туда, куда просили, — это ошибка, а не удобство.
        val service = ScriptedConfigService(
            listResult = Result.success(ConfigList(0L, listOf(activeConfig(id = "A")))),
        )
        val store = FileProfileStore(dir)
        val meta = FakeProfileMetaStore()

        val result = preparer(service, store, meta).ensureProfile("Z")

        assertEquals(PrepareResult.NoActiveConfig, result)
        assertEquals(0, service.fetchCount)
    }
}
