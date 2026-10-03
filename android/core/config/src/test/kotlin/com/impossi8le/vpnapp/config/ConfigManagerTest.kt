package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ConfigList
import com.impossi8le.vpnapp.domain.config.ConfigService
import com.impossi8le.vpnapp.domain.config.FetchedConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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

    private fun manager(config: FetchedConfig? = null, failure: Throwable? = null) =
        ConfigManager(FakeConfigService(config, failure), store())

    @Test
    fun `валидный конфиг применяется`() {
        val fetched = FetchedConfig(raw = valid, version = "v1", hash = sha256(valid))
        expectApply(manager(fetched), "v0", ApplyResult.Applied)
        assertNotNull(store().load())
    }

    @Test
    fun `та же версия пропускается без записи`() {
        val fetched = FetchedConfig(raw = valid, version = "v1", hash = sha256(valid))
        // Версия совпала — писать нечего, даже если бы конфиг отличался.
        expectApply(manager(fetched), "v1", ApplyResult.AlreadyCurrent)
    }

    @Test
    fun `невалидный конфиг отклоняется и старая версия остаётся`() {
        // Сначала кладём рабочую версию.
        val s = store()
        s.commit(s.stage(valid))

        val garbage = "это не конфиг".toByteArray()
        val fetched = FetchedConfig(raw = garbage, version = "v2", hash = sha256(garbage))
        val manager = ConfigManager(FakeConfigService(fetched), s)

        expectApply(manager, "v1", ApplyResult.Rejected)
        assertEquals(String(valid), String(store().load()!!.raw), "старая версия обязана остаться рабочей")
    }

    @Test
    fun `сбой скачивания не трогает установленный конфиг`() {
        val s = store()
        s.commit(s.stage(valid))

        val manager = ConfigManager(FakeConfigService(failure = IllegalStateException("сеть")), s)
        expectApply(manager, null, ApplyResult.Rejected)
        assertEquals(String(valid), String(store().load()!!.raw))
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
        expectApply(manager(fetched), null, ApplyResult.Applied)
    }

    @Test
    fun `хеш сравнивается без учёта регистра`() {
        val fetched = FetchedConfig(raw = valid, version = "v1", hash = sha256(valid).uppercase())
        expectApply(manager(fetched), null, ApplyResult.Applied)
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
    ) = runTest { assertEquals(expected, manager.apply("nl-ams-1", currentVersion)) }
}
