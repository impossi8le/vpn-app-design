package com.impossi8le.vpnapp.tunnel

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * Главный тест инварианта со стороны платформы: ни одно системное состояние
 * не превращается в зелёное.
 *
 * Если кто-то однажды решит, что «раз VpnService поднят, можно показать
 * зелёное», параметризованный тест упадёт на этой самой строке.
 */
class StatusMappingTest {

    @ParameterizedTest(name = "{0} не даёт зелёного")
    @EnumSource(SystemState::class)
    fun `ни одно системное состояние не даёт зелёного`(state: SystemState) {
        val status = state.toConnectionStatus()
        assertFalse(
            status.isProtected,
            "системное состояние $state не может давать зелёный — защита требует замера",
        )
    }

    @Test
    fun `поднятый туннель даёт «проверяем», а не «защищено»`() {
        assertTrue(SystemState.ESTABLISHED.toConnectionStatus() is ConnectionStatus.VerifyingProtection)
    }

    @Test
    fun `потеря туннеля возвращает в отключено`() {
        assertTrue(SystemState.LOST.toConnectionStatus() is ConnectionStatus.Disconnected)
    }

    @Test
    fun `состояние в покое — отключено`() {
        assertTrue(SystemState.IDLE.toConnectionStatus() is ConnectionStatus.Disconnected)
    }
}
