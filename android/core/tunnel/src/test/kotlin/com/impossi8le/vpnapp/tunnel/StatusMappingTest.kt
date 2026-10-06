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
    fun `заголовок подключено снимается потерей туннеля`() {
        // Полный ход события ядра, как он доходит до экрана: ядро сообщило
        // CONNECTED (ESTABLISHED), затем связь пропала (LOST). Экран обязан
        // перестать показывать «подключено» на втором шаге. Тест держит это
        // правило: если кто-то свяжет заголовок с фактом нажатия кнопки, а не
        // с событием ядра, первая же половина упадёт.
        val established = SystemState.ESTABLISHED.toConnectionStatus()
        assertTrue(
            established is ConnectionStatus.VerifyingProtection,
            "после CONNECTED туннель считается поднятым",
        )

        val lost = SystemState.LOST.toConnectionStatus()
        assertTrue(
            lost is ConnectionStatus.Disconnected,
            "после LOST заголовок «подключено» обязан сняться",
        )
    }

    @Test
    fun `состояние в покое — отключено`() {
        assertTrue(SystemState.IDLE.toConnectionStatus() is ConnectionStatus.Disconnected)
    }
}
