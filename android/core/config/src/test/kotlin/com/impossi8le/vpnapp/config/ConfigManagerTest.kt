package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ConfigFetchError
import com.impossi8le.vpnapp.domain.config.ConfigFetchException
import com.impossi8le.vpnapp.domain.config.ConfigList
import com.impossi8le.vpnapp.domain.config.ConfigService
import com.impossi8le.vpnapp.domain.config.FetchedConfig
import com.impossi8le.vpnapp.domain.config.ProfileMeta
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

private class FakeConfigService(
    private val config: FetchedConfig? = null,
    private val failure: Throwable? = null,
) : ConfigService {
    override suspend fun listConfigs(): Result<ConfigList> = Result.failure(NotImplementedError())
    override suspend fun fetchConfig(configId: String): Result<FetchedConfig> =
        if (failure != null) Result.failure(failure) else Result.success(config!!)
}

/** Фейк сервиса, падающий типизированной ошибкой получения конфига. */
private class FailingFetchService(private val error: ConfigFetchError) : ConfigService {
    override suspend fun listConfigs(): Result<ConfigList> = Result.failure(NotImplementedError())
    override suspend fun fetchConfig(configId: String): Result<FetchedConfig> =
        Result.failure(ConfigFetchException(error))
}

/**
 * Клиентская часть таблицы контракта §5: скачать → сверить версию →
 * валидировать → записать атомарно → проверить хеш.
 *
 * Каждая ветка защищает от своего отказа, поэтому проверяется отдельно.
 */
class ConfigManagerTest {

    @TempDir
    lateinit var dir: File

    private val valid = "client\ndev tun\nremote host 1194\n<ca>\nMIIB\n</ca>\n".toByteArray()

    private fun sha256(bytes: ByteArray) =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun store() = FileProfileStore(dir) { raw ->
        String(raw).contains("client") && String(raw).contains("</ca>")
    }

    private fun manager(
        config: FetchedConfig? = null,
        failure: Throwable? = null,
        meta: FakeProfileMetaStore = FakeProfileMetaStore(),
    ) = ConfigManager(FakeConfigService(config, failure), store(), meta)

    @Test
    fun `валидный конфиг применяется`() {
        val fetched = FetchedConfig(raw = valid, version = "v1", hash = sha256(valid))
        expectApply(manager(fetched), "v0", ApplyResult.Applied("v1"))
        assertNotNull(store().load())
    }

    @Test
    fun `та же версия пропускается без записи`() {
        val fetched = FetchedConfig(raw = valid, version = "v1", hash = sha256(valid))
        // Версия совпала — писать нечего, даже если бы конфиг отличался.
        expectApply(manager(fetched), "v1", ApplyResult.AlreadyCurrent("v1"))
    }

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

    @Test
    fun `невалидный конфиг отклоняется и старая версия остаётся`() {
        // Сначала кладём рабочую версию.
        val s = store()
        s.commit(s.stage(valid))

        val garbage = "это не конфиг".toByteArray()
        val fetched = FetchedConfig(raw = garbage, version = "v2", hash = sha256(garbage))
        val manager = ConfigManager(FakeConfigService(fetched), s, FakeProfileMetaStore())

        expectApply(manager, "v1", ApplyResult.Rejected)
        assertEquals(String(valid), String(store().load()!!.raw), "старая версия обязана остаться рабочей")
    }

    @Test
    fun `истёкшая подписка отличается от недоступной сети`() {
        // Контракт §5 разводит эти случаи: «подписка истекла» — состояние экрана,
        // конфиг не трогаем; «нет сети» — предложение повторить. Свести их к
        // одному исходу значит потерять реакцию UI.
        val expired = ConfigManager(FailingFetchService(ConfigFetchError.SubscriptionExpired), store(), FakeProfileMetaStore())
        val offline = ConfigManager(FailingFetchService(ConfigFetchError.NetworkUnavailable), store(), FakeProfileMetaStore())

        assertNotEquals(
            applyOf(expired),
            applyOf(offline),
            "истёкшая подписка и недоступная сеть обязаны различаться",
        )
    }

    @Test
    fun `отозванный конфиг отличается от истёкшей подписки`() {
        val revoked = ConfigManager(FailingFetchService(ConfigFetchError.ConfigRevoked), store(), FakeProfileMetaStore())
        val expired = ConfigManager(FailingFetchService(ConfigFetchError.SubscriptionExpired), store(), FakeProfileMetaStore())

        assertNotEquals(applyOf(revoked), applyOf(expired))
    }

    @Test
    fun `сбой скачивания не трогает установленный конфиг`() {
        val s = store()
        s.commit(s.stage(valid))

        // Недоступная сеть даёт типизированный исход, а не сведение к «конфиг
        // плохой»: экран предложит повторить, а не покажет порчу конфига.
        val manager = ConfigManager(FailingFetchService(ConfigFetchError.NetworkUnavailable), s, FakeProfileMetaStore())
        expectApply(manager, null, ApplyResult.FetchFailed(ConfigFetchError.NetworkUnavailable))

        assertEquals(
            String(valid),
            String(store().load()!!.raw),
            "установленный профиль обязан остаться нетронутым при любом сбое получения",
        )
    }

    @Test
    fun `несовпавший хеш откатывает запись`() {
        val fetched = FetchedConfig(raw = valid, version = "v1", hash = "sha256:deadbeef")
        val manager = manager(fetched)

        expectApply(manager, null, ApplyResult.HashMismatch)
        assertTrue(
            store().load() == null,
            "подозрительный конфиг не должен остаться на диске",
        )
    }

    @Test
    fun `отсутствие заголовка хеша не считается ошибкой`() {
        // Сверять нечего — это не повод считать конфиг испорченным.
        val fetched = FetchedConfig(raw = valid, version = "v1", hash = "")
        expectApply(manager(fetched), null, ApplyResult.Applied("v1"))
    }

    @Test
    fun `хеш сравнивается без учёта регистра`() {
        val fetched = FetchedConfig(raw = valid, version = "v1", hash = sha256(valid).uppercase())
        expectApply(manager(fetched), null, ApplyResult.Applied("v1"))
    }

    /**
     * Запускает применение и сверяет результат.
     *
     * `runTest` возвращает Unit, поэтому проверка идёт внутри него, а не через
     * возврат значения наружу.
     */
    private fun expectApply(
        manager: ConfigManager,
        currentVersion: String?,
        expected: ApplyResult,
        endDate: Long = 1_000_000L,
    ) = runTest { assertEquals(expected, manager.apply("nl-ams-1", currentVersion, endDate)) }

    /** Результат применения для тестов, сравнивающих исходы между собой. */
    private fun applyOf(manager: ConfigManager): ApplyResult =
        kotlinx.coroutines.runBlocking { manager.apply("nl-ams-1", null, 1_000_000L) }
}
