package com.impossi8le.vpnapp.tunnel

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionFailure
import com.impossi8le.vpnapp.domain.protection.ProtectionVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Пересмотр «подключено» по факту существования туннеля.
 *
 * Это проверка поведения, ради которого заведён весь сторож: состояние получено
 * событием ядра один раз, и при исчезновении туннеля оно обязано погаснуть — а не
 * висеть зелёным, пока трафик уже идёт открытым
 * (`docs/testing/2026-10-06-connected-without-tunnel.md`).
 */
class TunnelPresenceMappingTest {

    /** Состояние «туннель поднят» — то, что гасится при пропаже интерфейса. */
    private val tunnelUp = ConnectionStatus.VerifyingProtection

    @Test
    fun `исчезнувший туннель гасит подключено в отключено`() {
        assertEquals(
            ConnectionStatus.Disconnected,
            tunnelUp.reconcileWithTunnelPresence(TunnelPresence.ABSENT),
        )
    }

    @Test
    fun `исчезнувший туннель НЕ даёт «не удалось подключиться»`() {
        // Переход именно в Disconnected, а не Failed: соединение не «не
        // состоялось» — оно было и кончилось. «Не удалось подключиться» с кнопкой
        // «Повторить» — неверный текст про верный факт.
        val result = tunnelUp.reconcileWithTunnelPresence(TunnelPresence.ABSENT)
        assertFalse(result is ConnectionStatus.Failed)
    }

    @Test
    fun `живой туннель не трогаем`() {
        assertEquals(tunnelUp, tunnelUp.reconcileWithTunnelPresence(TunnelPresence.PRESENT))
    }

    @Test
    fun `неизвестность не доказательство отсутствия`() {
        // Сбой опроса не должен рубить исправный туннель.
        assertEquals(tunnelUp, tunnelUp.reconcileWithTunnelPresence(TunnelPresence.UNKNOWN))
    }

    @Test
    fun `подключение не гасится отсутствием интерфейса`() {
        // `Connecting` — интерфейса ещё нет по определению; проверка «туннеля
        // нет» гнала бы нормальный процесс в отключено.
        assertEquals(
            ConnectionStatus.Connecting,
            ConnectionStatus.Connecting.reconcileWithTunnelPresence(TunnelPresence.ABSENT),
        )
    }

    @Test
    fun `отключено не гасится дважды`() {
        assertEquals(
            ConnectionStatus.Disconnected,
            ConnectionStatus.Disconnected.reconcileWithTunnelPresence(TunnelPresence.ABSENT),
        )
    }

    @Test
    fun `замеренный зелёный тоже гасится при потере туннеля`() {
        // Замер не должен пережить исчезновение туннеля: он описывал соединение,
        // которого больше нет.
        val protected = ConnectionStatus.ProtectionFailed(ProtectionVerdict.Failed(ProtectionFailure.ProbeUnavailable))
        assertEquals(
            ConnectionStatus.Disconnected,
            protected.reconcileWithTunnelPresence(TunnelPresence.ABSENT),
        )
    }

    @ParameterizedTest(name = "{0} описывает поднятый туннель")
    @EnumSource(TunnelPresence::class)
    fun `зелёное не может появиться из пересмотра`(presence: TunnelPresence) {
        // §6 не слабеет: пересмотр не создаёт зелёное ни при каком факте.
        assertFalse(tunnelUp.reconcileWithTunnelPresence(presence).isProtected)
    }

    @Test
    fun `подключение не считается поднятым туннелем`() {
        assertFalse(ConnectionStatus.Connecting.describesTunnelUp)
        assertFalse(ConnectionStatus.Disconnected.describesTunnelUp)
        assertTrue(ConnectionStatus.VerifyingProtection.describesTunnelUp)
    }
}
