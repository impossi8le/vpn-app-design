package com.impossi8le.vpnapp.tile

import android.service.quicksettings.Tile
import com.impossi8le.vpnapp.domain.tunnel.TunnelState
import com.impossi8le.vpnapp.vpnservice.TunnelStateSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Плитка «Быстрых настроек»: соответствие состояния туннеля и того, что видит
 * пользователь.
 *
 * Проверяется на JVM: сам `TileService` поднимается только системой, а решения
 * «как выглядит плитка» и «как разбирается состояние сервиса» — чистые, и
 * ошибиться в них легко.
 */
class VpnTileServiceTest {

    private fun String.stateOf() = TunnelStateSnapshot.fromSystemName(this)

    @Test
    fun `поднятый туннель разбирается и показывается включённым`() {
        // `ESTABLISHED` — «интерфейс поднят». Это НЕ защита: подпись об этом
        // молчит, и тест это фиксирует.
        assertEquals(TunnelState.Established, "ESTABLISHED".stateOf())
        assertEquals(
            Tile.STATE_ACTIVE,
            tilePresentation(TunnelState.Established).state,
        )
    }

    @Test
    fun `идущее подключение не рисуется ни включённым, ни недоступным`() {
        // Показать «включено», пока туннель только поднимается, значило бы
        // обещать связь, которой ещё нет. Но и `UNAVAILABLE` не годится: система
        // рисует его как ВЫКЛЮЧЕННУЮ и не даёт нажать — ровно жалоба из
        // issue #16, пункт 3. Честный выбор — обычная плитка с явной подписью.
        assertEquals(TunnelState.Connecting, "CONNECTING".stateOf())
        val connecting = tilePresentation(TunnelState.Connecting)
        assertNotEquals(Tile.STATE_ACTIVE, connecting.state)
        assertNotEquals(Tile.STATE_UNAVAILABLE, connecting.state)
    }

    @Test
    fun `подключение и выключение различаются ТЕКСТОМ, а не только оттенком`() {
        // Три визуальных состояния плитки мало, чтобы отличить процесс от
        // покоя, поэтому различие обязано быть в подписи. Это и есть носитель
        // «однозначно читается» из пункта 3.
        val connecting = tilePresentation(TunnelState.Connecting)
        val idle = tilePresentation(TunnelState.Idle)

        assertNotEquals(idle.label, connecting.label)
        assertNotEquals(idle.description, connecting.description)
        assertTrue(connecting.label.isNotBlank())
        assertTrue(connecting.description!!.isNotBlank())
    }

    @Test
    fun `восстановление не выдаёт себя за обычное подключение`() {
        // Пользователь уже видел поднятый туннель; «подключаемся» скрыло бы,
        // что связь пропадала и сейчас трафик идёт напрямую.
        assertEquals(TunnelState.Recovering, "RECOVERING".stateOf())
        assertNotEquals(
            tilePresentation(TunnelState.Connecting).label,
            tilePresentation(TunnelState.Recovering).label,
        )
    }

    @Test
    fun `ни одна подпись не обещает защиту`() {
        // §6: поднятый интерфейс не доказывает, что трафик идёт через него.
        // Слово «защищено» на плитке недопустимо ни в одном состоянии.
        TunnelState.entries
            .map { tilePresentation(it) }
            .forEach { p ->
                assertFalse(
                    p.label.contains("защищ", ignoreCase = true),
                    "подпись «${p.label}» обещает защиту",
                )
                assertFalse(
                    p.description.orEmpty().contains("защищен", ignoreCase = true),
                    "описание «${p.description}» обещает защиту",
                )
            }
    }

    @Test
    fun `незнание не считается выключенным состоянием`() {
        // Самое важное здесь: незнание — это «не знаем», а не «выключено».
        // Считать его выключенным значило бы показать неактивной плитку при уже
        // поднятом туннеле — ровно то, ради чего общий снимок и заведён.
        assertEquals(TunnelState.Unknown, TunnelStateSnapshot.fromSystemName(null))
        assertEquals(TunnelState.Unknown, "ЧТО-ТО-НОВОЕ".stateOf())
    }

    @Test
    fun `снятый туннель показывается выключенным`() {
        listOf("IDLE", "LOST", "FAILED").forEach { raw ->
            assertEquals(TunnelState.Idle, raw.stateOf(), "для состояния $raw")
        }
    }
}
