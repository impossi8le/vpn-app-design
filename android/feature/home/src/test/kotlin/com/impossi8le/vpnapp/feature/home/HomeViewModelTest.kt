package com.impossi8le.vpnapp.feature.home

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionGate
import com.impossi8le.vpnapp.testsupport.FactProbe
import com.impossi8le.vpnapp.testsupport.FakeTunnelControlling
import com.impossi8le.vpnapp.testsupport.ThrowingProbe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * Ловушка на ложную защиту.
 *
 * Эти тесты падают, если кто-то однажды решит, что «туннель поднят, значит
 * можно показать зелёное»: зелёное здесь появляется ТОЛЬКО когда проба
 * подтвердила все три признака.
 */
class HomeViewModelTest {

    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherRule()

    @Test
    fun `поднятый туннель сам по себе не даёт зелёного`() = runTest {
        val tunnel = FakeTunnelControlling()
        // Проба отрицательная: маршруты выглядят правильно, но замер не подтвердил.
        val vm = HomeViewModel(tunnel, ProtectionGate(FactProbe(Triple(true, false, true))))

        vm.connect()

        assertFalse(
            vm.status.value.isProtected,
            "системное состояние туннеля не может давать зелёное",
        )
    }

    @Test
    fun `зелёное появляется только после подтверждения пробы`() = runTest {
        val tunnel = FakeTunnelControlling()
        val vm = HomeViewModel(tunnel, ProtectionGate(FactProbe(Triple(true, true, true))))

        vm.connect()

        assertTrue(vm.status.value.isProtected)
    }

    @Test
    fun `провал пробы даёт ProtectionFailed, а не зависание и не зелёное`() = runTest {
        val tunnel = FakeTunnelControlling()
        val vm = HomeViewModel(tunnel, ProtectionGate(FactProbe(Triple(false, false, false))))

        vm.connect()

        assertTrue(vm.status.value is ConnectionStatus.ProtectionFailed)
    }

    @Test
    fun `сбой пробы не оставляет экран в состоянии проверки навсегда`() = runTest {
        val tunnel = FakeTunnelControlling()
        val vm = HomeViewModel(tunnel, ProtectionGate(ThrowingProbe()))

        vm.connect()

        assertTrue(
            vm.status.value is ConnectionStatus.ProtectionFailed,
            "молчание пробы — провал защиты, а не вечное «проверяем»",
        )
    }

    @Test
    fun `смена сети сбрасывает зелёное, а не сохраняет его`() = runTest {
        val tunnel = FakeTunnelControlling()
        val probe = FactProbe(Triple(true, true, true))
        val vm = HomeViewModel(tunnel, ProtectionGate(probe))

        vm.connect()
        assertTrue(vm.status.value.isProtected)

        // Сеть сменилась: маршруты уехали. Даже без явного reverify() туннель
        // обязан сообщить об этом, и зелёное не должно пережить изменение.
        tunnel.emit(ConnectionStatus.VerifyingProtection)
        assertFalse(vm.status.value.isProtected)

        // Перепроверка с уже провалившимися фактами зелёное не возвращает.
        probe.withFacts(ipv4 = true, ipv6 = false, dns = true)
        vm.reverify()
        assertFalse(vm.status.value.isProtected)
        assertEquals(1, tunnel.reverifyCount, "перепроверка обязана дойти до туннеля")
    }

    @Test
    fun `перепроверка при восстановившихся фактах возвращает зелёное`() = runTest {
        val tunnel = FakeTunnelControlling()
        val probe = FactProbe(Triple(true, true, true))
        val vm = HomeViewModel(tunnel, ProtectionGate(probe))

        vm.connect()
        probe.withFacts(ipv4 = true, ipv6 = false, dns = true)
        vm.reverify()
        assertFalse(vm.status.value.isProtected)

        // Сеть восстановилась — зелёное возвращается только по замеру.
        probe.withFacts(ipv4 = true, ipv6 = true, dns = true)
        vm.reverify()
        assertTrue(vm.status.value.isProtected)
    }

    @Test
    fun `отключение уводит экран из зелёного`() = runTest {
        val tunnel = FakeTunnelControlling()
        val vm = HomeViewModel(tunnel, ProtectionGate(FactProbe(Triple(true, true, true))))

        vm.connect()
        assertTrue(vm.status.value.isProtected)

        vm.disconnect()
        assertTrue(vm.status.value is ConnectionStatus.Disconnected)
        assertEquals(1, tunnel.disconnectCount)
    }
}
