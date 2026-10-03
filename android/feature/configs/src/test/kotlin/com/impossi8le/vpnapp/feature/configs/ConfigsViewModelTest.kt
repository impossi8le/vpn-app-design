package com.impossi8le.vpnapp.feature.configs

import com.impossi8le.vpnapp.domain.config.ConfigList
import com.impossi8le.vpnapp.domain.config.ConfigService
import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.FetchedConfig
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private class FakeConfigService(
    private val list: ConfigList? = null,
    private val failure: Throwable? = null,
) : ConfigService {
    override suspend fun listConfigs(): Result<ConfigList> =
        if (failure != null) Result.failure(failure) else Result.success(list!!)

    override suspend fun fetchConfig(configId: String): Result<FetchedConfig> =
        Result.failure(NotImplementedError())
}

private fun config(id: String, status: SubscriptionStatus) = ConfigSummary(
    id = id,
    name = "Нидерланды · $id",
    countryCode = "NL",
    city = id,
    startDateEpochSeconds = 1_700_000_000L,
    endDateEpochSeconds = 1_800_000_000L,
    status = status,
)

class ConfigsViewModelTest {

    private val scheduler = TestCoroutineScheduler()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `статус у каждого подключения свой`() = runTest(scheduler) {
        val service = FakeConfigService(
            list = ConfigList(
                chatId = 1L,
                configs = listOf(
                    config("nl-ams-1", SubscriptionStatus.ACTIVE),
                    config("nl-rtm-1", SubscriptionStatus.EXPIRED),
                ),
            ),
        )
        val vm = ConfigsViewModel(service)
        vm.refresh()

        val state = vm.state.value as ConfigsUiState.Ready
        // Истёкшее подключение не должно скрывать действующее.
        assertEquals(SubscriptionStatus.ACTIVE, state.configs.first { it.id == "nl-ams-1" }.status)
        assertEquals(SubscriptionStatus.EXPIRED, state.configs.first { it.id == "nl-rtm-1" }.status)
        assertEquals(1, vm.state.value.activeCount)
    }

    @Test
    fun `действующие подключения идут первыми`() = runTest(scheduler) {
        val service = FakeConfigService(
            list = ConfigList(
                chatId = 1L,
                configs = listOf(
                    config("expired", SubscriptionStatus.EXPIRED),
                    config("active", SubscriptionStatus.ACTIVE),
                ),
            ),
        )
        val vm = ConfigsViewModel(service)
        vm.refresh()

        val configs = (vm.state.value as ConfigsUiState.Ready).configs
        assertEquals("active", configs.first().id, "рабочее подключение должно быть сверху")
    }

    @Test
    fun `пустой список это состояние «подписок нет», а не ошибка`() = runTest(scheduler) {
        val vm = ConfigsViewModel(FakeConfigService(list = ConfigList(chatId = 1L, configs = emptyList())))
        vm.refresh()

        assertEquals(ConfigsUiState.Empty, vm.state.value)
    }

    @Test
    fun `сбой загрузки помечается как повторяемый`() = runTest(scheduler) {
        val vm = ConfigsViewModel(FakeConfigService(failure = IllegalStateException("нет сети")))
        vm.refresh()

        val state = vm.state.value
        assertTrue(state is ConfigsUiState.Failed)
        assertTrue((state as ConfigsUiState.Failed).retryable, "без сети имеет смысл предложить повтор")
    }

    @Test
    fun `отозванные подключения не считаются действующими`() = runTest(scheduler) {
        val service = FakeConfigService(
            list = ConfigList(
                chatId = 1L,
                configs = listOf(
                    config("revoked", SubscriptionStatus.REVOKED),
                    config("pending", SubscriptionStatus.PENDING),
                ),
            ),
        )
        val vm = ConfigsViewModel(service)
        vm.refresh()

        assertEquals(0, vm.state.value.activeCount, "ни отозванное, ни ожидающее не является рабочим")
    }

    @Test
    fun `в состоянии нет призыва к покупке`() = runTest(scheduler) {
        // Поля для продажи отсутствуют в контракте намеренно: клиент не рендерит
        // «продлить». Проверяем, что модель не тащит его косвенно.
        val vm = ConfigsViewModel(
            FakeConfigService(
                list = ConfigList(1L, listOf(config("only", SubscriptionStatus.EXPIRED))),
            ),
        )
        vm.refresh()

        val text = vm.state.value.toString()
        listOf("buy", "purchase", "subscribe", "продлить", "купить").forEach {
            assertTrue(!text.contains(it, ignoreCase = true), "в состоянии не должно быть призыва «$it»")
        }
    }

    @Test
    fun `повторный refresh заменяет данные, а не дополняет их`() = runTest(scheduler) {
        val service = FakeConfigService(
            list = ConfigList(1L, listOf(config("a", SubscriptionStatus.ACTIVE))),
        )
        val vm = ConfigsViewModel(service)
        vm.refresh()
        vm.refresh()

        assertEquals(1, (vm.state.value as ConfigsUiState.Ready).configs.size)
    }
}
