package com.impossi8le.vpnapp.domain.tunnel

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Подсказка про Always-on VPN.
 *
 * Причина, по которой это вообще существует: у приложения нет киллсвитча уровня
 * ОС. Смерть foreground-сервиса снимает блокировку, и трафик уходит открытым.
 * Единственная защита, которая это переживает, настраивается пользователем, — и
 * значит о ней надо попросить, а не надеяться, что он найдёт её сам.
 */
class AlwaysOnGuidanceTest {

    private fun protected() = ConnectionStatus.Protected(
        (ProtectionVerdict.evaluate(true, true, true) as ProtectionVerdict.Confirmed).evidence,
    )

    @Test
    fun `на подтверждённой защите подсказка показывается`() {
        assertEquals(
            AlwaysOnHint.SUGGEST,
            AlwaysOnGuidance.hint(protected(), alreadyEnabled = false, dismissed = false),
        )
    }

    @Test
    fun `если настройка уже включена, подсказки нет`() {
        assertEquals(
            AlwaysOnHint.NONE,
            AlwaysOnGuidance.hint(protected(), alreadyEnabled = true, dismissed = false),
        )
    }

    @Test
    fun `отклонённая подсказка не возвращается`() {
        // Навязчивость вредна: пользователь пришёл за VPN, а не за настройкой системы.
        assertEquals(
            AlwaysOnHint.NONE,
            AlwaysOnGuidance.hint(protected(), alreadyEnabled = false, dismissed = true),
        )
    }

    @Test
    fun `без поднятого туннеля подсказки нет`() {
        val states = listOf(
            ConnectionStatus.Disconnected,
            ConnectionStatus.Connecting,
            ConnectionStatus.Failed("нет сети"),
            ConnectionStatus.ProtectionFailed(
                ProtectionVerdict.evaluate(false, true, true) as ProtectionVerdict.Failed,
            ),
        )

        states.forEach { status ->
            assertEquals(
                AlwaysOnHint.NONE,
                AlwaysOnGuidance.hint(status, alreadyEnabled = false, dismissed = false),
                "предлагать настройку при состоянии $status бессмысленно и пугает",
            )
        }
    }

    @Test
    fun `поднятый туннель уже достаточен для подсказки`() {
        // Изменено 2026-10-15. Раньше здесь ожидалось NONE — и это делало
        // подсказку невидимой для всех: `Protected` требует замера, а щуп —
        // заглушка. Единственная настройка, переживающая смерть сервиса, не
        // предлагалась никому. Подсказка про Always-on осмысленна, как только
        // туннель поднят, — подтверждение замера для неё не требуется.
        assertEquals(
            AlwaysOnHint.SUGGEST,
            AlwaysOnGuidance.hint(
                ConnectionStatus.VerifyingProtection,
                alreadyEnabled = false,
                dismissed = false,
            ),
        )
    }
}
