package com.impossi8le.vpnapp.feature.home

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionGate
import com.impossi8le.vpnapp.domain.protection.ProtectionProbe
import com.impossi8le.vpnapp.domain.protection.ReverificationPolicy
import com.impossi8le.vpnapp.testsupport.FactProbe
import com.impossi8le.vpnapp.testsupport.FakeTunnelControlling
import com.impossi8le.vpnapp.testsupport.ThrowingProbe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.time.Duration.Companion.seconds

/**
 * Ловушка на ложную защиту.
 *
 * Эти тесты падают, если кто-то однажды решит, что «туннель поднят, значит
 * можно показать зелёное»: зелёное здесь появляется ТОЛЬКО когда проба
 * подтвердила все три признака.
 *
 * Планировщик берётся у правила: если у диспетчера и у теста разные
 * планировщики, коллектор внутри ViewModel не возобновляется и состояние
 * застревает на первом значении.
 *
 * Каждый тест начинает наблюдение через [HomeViewModel.start] и завершает
 * через [HomeViewModel.stop] — иначе таймер перепроверки остаётся жить, и
 * `runTest` дожидается его бесконечно.
 */
class HomeViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherRule()

    private fun viewModel(tunnel: FakeTunnelControlling, probe: ProtectionProbe) = HomeViewModel(
        tunnel,
        ProtectionGate(probe),
        // Таймер в этих тестах не нужен: он проверяется отдельно.
        ReverificationPolicy(interval = 60.seconds, onNetworkChange = true),
    )

    @Test
    fun `поднятый туннель сам по себе не даёт зелёного`() = runTest(mainDispatcher.scheduler) {
        val tunnel = FakeTunnelControlling()
        // Проба отрицательная: маршруты выглядят правильно, но замер не подтвердил.
        val vm = viewModel(tunnel, FactProbe(Triple(true, false, true)))
        vm.start()

        vm.connect()

        assertFalse(
            vm.status.value.isProtected,
            "системное состояние туннеля не может давать зелёное",
        )
        vm.stop()
    }

    @Test
    fun `зелёное появляется только после подтверждения пробы`() = runTest(mainDispatcher.scheduler) {
        val tunnel = FakeTunnelControlling()
        val vm = viewModel(tunnel, FactProbe(Triple(true, true, true)))
        vm.start()

        vm.connect()

        assertTrue(vm.status.value.isProtected)
        vm.stop()
    }

    @Test
    fun `провал пробы даёт ProtectionFailed, а не зависание и не зелёное`() =
        runTest(mainDispatcher.scheduler) {
            val tunnel = FakeTunnelControlling()
            val vm = viewModel(tunnel, FactProbe(Triple(false, false, false)))
            vm.start()

            vm.connect()

            assertTrue(vm.status.value is ConnectionStatus.ProtectionFailed)
            vm.stop()
        }

    @Test
    fun `сбой пробы не оставляет экран в состоянии проверки навсегда`() =
        runTest(mainDispatcher.scheduler) {
            val tunnel = FakeTunnelControlling()
            val vm = viewModel(tunnel, ThrowingProbe())
            vm.start()

            vm.connect()

            assertTrue(
                vm.status.value is ConnectionStatus.ProtectionFailed,
                "молчание пробы — провал защиты, а не вечное «проверяем»",
            )
            vm.stop()
        }

    @Test
    fun `смена сети сбрасывает зелёное, а не сохраняет его`() = runTest(mainDispatcher.scheduler) {
        val tunnel = FakeTunnelControlling()
        val probe = FactProbe(Triple(true, true, true))
        val vm = viewModel(tunnel, probe)
        vm.start()

        vm.connect()
        assertTrue(vm.status.value.isProtected)

        // Сеть сменилась, но статус остался прежним — этот случай StateFlow
        // схлопнул бы, поэтому сигнал идёт отдельным каналом.
        tunnel.emitNetworkChange()
        assertFalse(vm.status.value.isProtected, "зелёное не должно пережить смену сети")

        // Перепроверка с провалившимися фактами зелёное не возвращает.
        probe.withFacts(ipv4 = true, ipv6 = false, dns = true)
        vm.reverify()
        assertFalse(vm.status.value.isProtected)
        vm.stop()
    }

    @Test
    fun `перепроверка при восстановившихся фактах возвращает зелёное`() =
        runTest(mainDispatcher.scheduler) {
            val tunnel = FakeTunnelControlling()
            val probe = FactProbe(Triple(true, true, true))
            val vm = viewModel(tunnel, probe)
            vm.start()

            vm.connect()
            probe.withFacts(ipv4 = true, ipv6 = false, dns = true)
            vm.reverify()
            assertFalse(vm.status.value.isProtected)

            // Сеть восстановилась — зелёное возвращается только по замеру.
            probe.withFacts(ipv4 = true, ipv6 = true, dns = true)
            vm.reverify()
            assertTrue(vm.status.value.isProtected)
            vm.stop()
        }

    @Test
    fun `отключение уводит экран из зелёного`() = runTest(mainDispatcher.scheduler) {
        val tunnel = FakeTunnelControlling()
        val vm = viewModel(tunnel, FactProbe(Triple(true, true, true)))
        vm.start()

        vm.connect()
        assertTrue(vm.status.value.isProtected)

        vm.disconnect()
        assertTrue(vm.status.value is ConnectionStatus.Disconnected)
        assertEquals(1, tunnel.disconnectCount)
        vm.stop()
    }

    @Test
    fun `до start наблюдение не идёт`() = runTest(mainDispatcher.scheduler) {
        // Опрос сети не должен начинаться раньше, чем экран показан.
        val tunnel = FakeTunnelControlling()
        val vm = viewModel(tunnel, FactProbe(Triple(true, true, true)))

        assertTrue(
            vm.status.value is ConnectionStatus.Disconnected,
            "без start состояние остаётся исходным",
        )
    }

    @Test
    fun `повторный start не удваивает наблюдение`() = runTest(mainDispatcher.scheduler) {
        val tunnel = FakeTunnelControlling()
        val vm = viewModel(tunnel, FactProbe(Triple(true, true, true)))
        vm.start()
        vm.start()

        vm.connect()

        // Второй start не должен добавлять вторую подписку на смену сети.
        assertTrue(vm.status.value.isProtected)
        vm.stop()
    }
}
