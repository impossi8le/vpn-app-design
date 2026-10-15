package com.impossi8le.vpnapp.domain.settings

import com.impossi8le.vpnapp.domain.tunnel.AlwaysOnHint
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Стоит ли показывать подсказку про Always-on.
 *
 * Само правило «когда предлагать» живёт в [com.impossi8le.vpnapp.domain.tunnel.AlwaysOnGuidance]
 * и проверяется там. Здесь — только хранение отказа: без него подсказка,
 * отклонённая один раз, возвращалась бы при каждом подключении.
 */
class ShouldSuggestAlwaysOnTest {

    @Test
    fun `предложение при отсутствии отказа показывается`() {
        assertTrue(shouldSuggestAlwaysOn(AlwaysOnHint.SUGGEST, dismissed = false))
    }

    @Test
    fun `отклонённое предложение не возвращается`() {
        // Главное здесь: отказ помнится, а не действует до конца сессии.
        assertFalse(shouldSuggestAlwaysOn(AlwaysOnHint.SUGGEST, dismissed = true))
    }

    @Test
    fun `без предложения показывать нечего`() {
        assertFalse(shouldSuggestAlwaysOn(AlwaysOnHint.NONE, dismissed = false))
        assertFalse(shouldSuggestAlwaysOn(AlwaysOnHint.NONE, dismissed = true))
    }
}
