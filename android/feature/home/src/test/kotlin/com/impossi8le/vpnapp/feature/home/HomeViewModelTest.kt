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
 * Наблюдение обязательно останавливается в `finally`: таймер перепроверки —
 * бесконечный цикл, и если тест упадёт на утверждении до `stop()`, `runTest`
 * будет ждать его вечно, превращая падение теста в зависшую джобу.
 */
class HomeViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherRule()

    private fun viewModel(tunnel: FakeTunnelControlling, probe: ProtectionProbe) = HomeViewModel(
        tunnel,
        ProtectionGate(probe),
        // Большой интервал: периодический таймер в этих тестах не участвует,
        // он проверяется отдельно в ProtectionWatcherTest.
        ReverificationPolicy(interval = 300.seconds, onNetworkChange = true),
    )

    @Test
    fun `поднятый туннель сам по себе не даёт зелёного`() = runTest(mainDispatcher.scheduler) {
        val tunnel = FakeTunnelControlling()
        // Проба отрицательная: маршруты выглядят правильно, но замер не подтвердил.
        val vm = viewModel(tunnel, FactProbe(Triple(true, false, true)))
        vm.start()
        try {
            vm.connect()

            assertFalse(
                vm.status.value.isProtected,
                "системное состояние туннеля не может давать зелёное",
            )
        } finally {
            vm.stop()
        }
    }

    @Test
    fun `зелёное появляется только после подтверждения пробы`() = runTest(mainDispatcher.scheduler) {
        val tunnel = FakeTunnelControlling()
        val vm = viewModel(tunnel, FactProbe(Triple(true, true, true)))
        vm.start()
        try {
            vm.connect()

            assertTrue(vm.status.value.isProtected)
        } finally {
            vm.stop()
        }
    }

    @Test
    fun `провал пробы даёт ProtectionFailed, а не зависание и не зелёное`() =
        runTest(mainDispatcher.scheduler) {
            val tunnel = FakeTunnelControlling()
            val vm = viewModel(tunnel, FactProbe(Triple(false, false, false)))
            vm.start()
            try {
                vm.connect()

                assertTrue(vm.status.value is ConnectionStatus.ProtectionFailed)
            } finally {
                vm.stop()
            }
        }

    @Test
    fun `сбой пробы не оставляет экран в состоянии проверки навсегда`() =
        runTest(mainDispatcher.scheduler) {
            val tunnel = FakeTunnelControlling()
            val vm = viewModel(tunnel, ThrowingProbe())
            vm.start()
            try {
                vm.connect()

                assertTrue(
                    vm.status.value is ConnectionStatus.ProtectionFailed,
                    "молчание пробы — провал защиты, а не вечное «проверяем»",
                )
            } finally {
                vm.stop()
            }
        }

    @Test
    fun `смена сети сбрасывает зелёное, а не сохраняет его`() = runTest(mainDispatcher.scheduler) {
        val tunnel = FakeTunnelControlling()
        val probe = FactProbe(Triple(true, true, true))
        val vm = viewModel(tunnel, probe)
        vm.start()
        try {
            vm.connect()
            assertTrue(vm.status.value.isProtected)

            // Сеть сменилась: маршруты больше не закрывают IPv6. Факты меняются
            // ДО события — иначе перепроверка честно вернула бы зелёное, и тест
            // проверял бы не то.
            probe.withFacts(ipv4 = true, ipv6 = false, dns = true)

            // Статус туннеля при этом не менялся — этот случай StateFlow
            // схлопнул бы, поэтому сигнал идёт отдельным каналом.
            tunnel.emitNetworkChange()

            assertFalse(vm.status.value.isProtected, "зелёное не должно пережить смену сети")
        } finally {
            vm.stop()
        }
    }

    @Test
    fun `перепроверка при восстановившихся фактах возвращает зелёное`() =
        runTest(mainDispatcher.scheduler) {
            val tunnel = FakeTunnelControlling()
            val probe = FactProbe(Triple(true, true, true))
            val vm = viewModel(tunnel, probe)
            vm.start()
            try {
                vm.connect()
                probe.withFacts(ipv4 = true, ipv6 = false, dns = true)
                vm.reverify()
                assertFalse(vm.status.value.isProtected)

                // Сеть восстановилась — зелёное возвращается только по замеру.
                probe.withFacts(ipv4 = true, ipv6 = true, dns = true)
                vm.reverify()
                assertTrue(
                    vm.status.value.isProtected,
                    "свежий успешный замер обязан показываться, иначе индикатор залипает в «не проверено»",
                )
            } finally {
                vm.stop()
            }
        }

    @Test
    fun `отключение уводит экран из зелёного`() = runTest(mainDispatcher.scheduler) {
        val tunnel = FakeTunnelControlling()
        val vm = viewModel(tunnel, FactProbe(Triple(true, true, true)))
        vm.start()
        try {
            vm.connect()
            assertTrue(vm.status.value.isProtected)

            vm.disconnect()
            assertTrue(vm.status.value is ConnectionStatus.Disconnected)
            assertEquals(1, tunnel.disconnectCount)
        } finally {
            vm.stop()
        }
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
        try {
            vm.start()

            vm.connect()

            assertTrue(vm.status.value.isProtected)
        } finally {
            vm.stop()
        }
    }
}
