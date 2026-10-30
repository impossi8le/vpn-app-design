package com.impossi8le.vpnapp.tile

import com.impossi8le.vpnapp.domain.tunnel.TunnelState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Перерисовка плитки, пока шторка открыта.
 *
 * Проверяется на JVM: сам `TileService` поднимается только системой, а решение
 * «когда перерисовать» — обычная подписка на поток, и ошибиться в ней легко.
 * Именно эта ошибка и была в issue #53: плитка читала состояние один раз, при
 * открытии шторки, и дальше не менялась.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TileStateRefresherTest {

    @Test
    fun `при открытии шторки рисует текущее состояние сразу`() = runTest {
        // Без первого кадра плитка открылась бы с прежним рисунком системы и
        // «догнала» состояние только на следующем событии, которого может не быть.
        val states = MutableStateFlow(TunnelState.Idle)
        val drawn = mutableListOf<TunnelState>()
        val refresher = TileStateRefresher()

        refresher.start(backgroundScope, states, drawn::add)
        runCurrent()

        assertEquals(listOf(TunnelState.Idle), drawn)
    }

    @Test
    fun `перерисовывает на каждое изменение состояния`() = runTest {
        // Это и есть #53: пока шторка открыта, сервис публикует CONNECTING и
        // ESTABLISHED, а плитка обязана их показать. Раньше подпись замирала на
        // прочитанном при открытии и тумблер выглядел сломанным.
        val states = MutableStateFlow(TunnelState.Idle)
        val drawn = mutableListOf<TunnelState>()
        val refresher = TileStateRefresher()

        refresher.start(backgroundScope, states, drawn::add)
        runCurrent()

        states.value = TunnelState.Connecting
        runCurrent()
        states.value = TunnelState.Established
        runCurrent()

        assertEquals(
            listOf(
                TunnelState.Idle,
                TunnelState.Connecting,
                TunnelState.Established,
            ),
            drawn,
        )
    }

    @Test
    fun `повторный start не оставляет второй подписки`() = runTest {
        // `onStartListening` зовётся на каждое открытие шторки. Без отмены прежней
        // подписки их копилось бы по одной на открытие, и число отрисовок на
        // каждое изменение умножалось бы.
        val states = MutableStateFlow(TunnelState.Idle)
        val drawn = mutableListOf<TunnelState>()
        val refresher = TileStateRefresher()

        refresher.start(backgroundScope, states, drawn::add)
        runCurrent()
        refresher.start(backgroundScope, states, drawn::add)
        runCurrent()

        states.value = TunnelState.Established
        runCurrent()

        assertEquals(
            listOf(
                // Первая подписка успела нарисовать начальное значение до отмены.
                TunnelState.Idle,
                // После перезапуска — ровно один кадр на изменение.
                TunnelState.Idle,
                TunnelState.Established,
            ),
            drawn,
        )
    }

    @Test
    fun `после stop изменения больше не рисуются`() = runTest {
        // Шторка закрыта — плитку рисовать некому и незачем. Живая подписка
        // дёргала бы систему после `onStopListening`.
        val states = MutableStateFlow(TunnelState.Idle)
        val drawn = mutableListOf<TunnelState>()
        val refresher = TileStateRefresher()

        refresher.start(backgroundScope, states, drawn::add)
        runCurrent()
        refresher.stop()

        states.value = TunnelState.Established
        runCurrent()

        assertEquals(listOf(TunnelState.Idle), drawn)
    }
}
