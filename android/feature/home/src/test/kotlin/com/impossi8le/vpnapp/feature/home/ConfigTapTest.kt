package com.impossi8le.vpnapp.feature.home

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Куда ведёт тап по строке подключения.
 *
 * Регресс issue #19: раньше тап по НЕвыбранной строке шёл в тихий выбор, а
 * подтверждение переключения доставалось только строке, уже выбранной. Отсюда
 * «попап работает только в определённой зоне». Тесты фиксируют, что оба пути
 * теперь решаются одним правилом и по признаку «есть живое соединение», а не по
 * признаку «строка выделена».
 */
class ConfigTapTest {

    @Test
    fun `без живого соединения тап по невыбранной строке только выбирает`() {
        assertEquals(
            ConfigTapRouting.Choose("de-fra-1"),
            routeConfigTap(
                tappedId = "de-fra-1",
                selectedId = "nl-ams-1",
                liveConnection = false,
            ),
        )
    }

    @Test
    fun `при живом соединении тап по ДРУГОЙ строке ведёт в смену подключения`() {
        // Это и есть починка #19: невыбранная строка при поднятом туннеле больше
        // не «тихо выбирается», а попадает в путь смены — тот же, что у выбранной.
        assertEquals(
            ConfigTapRouting.Switch("de-fra-1"),
            routeConfigTap(
                tappedId = "de-fra-1",
                selectedId = "nl-ams-1",
                liveConnection = true,
            ),
        )
    }

    @Test
    fun `тап по работающему подключению не переключает`() {
        assertEquals(
            ConfigTapRouting.AlreadyRunning("nl-ams-1"),
            routeConfigTap(
                tappedId = "nl-ams-1",
                selectedId = "nl-ams-1",
                liveConnection = true,
            ),
        )
    }

    @Test
    fun `поднимающийся туннель считается живым`() {
        // Connecting/Recovering — это идущая работа с соединением, и смена цели в
        // эти секунды так же рвёт начатое, поэтому путь остаётся «смена».
        assertEquals(
            ConfigTapRouting.Switch("de-fra-1"),
            routeConfigTap("de-fra-1", "nl-ams-1", liveConnection = true),
        )
    }

    @Test
    fun `выбор не меняется от того, что строка уже выделена`() {
        // Ключевое свойство починки: без живого соединения выбранная и невыбранная
        // строка ведут себя одинаково — обе только выбирают.
        val chosen = routeConfigTap("nl-ams-1", "nl-ams-1", liveConnection = false)
        assertEquals(ConfigTapRouting.Choose("nl-ams-1"), chosen)
    }

    @Test
    fun `живым считается всё, кроме отключено и сбой`() {
        assertFalse(ConnectionStatus.Disconnected.hasLiveConnection)
        assertFalse(ConnectionStatus.Failed("boom").hasLiveConnection)

        assertTrue(ConnectionStatus.Connecting.hasLiveConnection)
        assertTrue(ConnectionStatus.Recovering.hasLiveConnection)
        assertTrue(ConnectionStatus.VerifyingProtection.hasLiveConnection)
    }
}
