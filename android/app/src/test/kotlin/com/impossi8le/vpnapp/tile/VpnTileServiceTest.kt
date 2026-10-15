package com.impossi8le.vpnapp.tile

import com.impossi8le.vpnapp.vpnservice.TunnelStateSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Плитка «Быстрых настроек»: соответствие состояния туннеля и того, что видит
 * пользователь.
 *
 * Проверяется на JVM: сам `TileService` поднимается только системой, а решение
 * «как выглядит плитка» — чистое отображение, и ошибиться в нём легко.
 */
class VpnTileServiceTest {

    @Test
    fun `поднятый туннель показывается включённым`() {
        assertEquals(
            TunnelTileState.Connected,
            TunnelTileState.from(TunnelStateSnapshot.State.Connected),
        )
    }

    @Test
    fun `идущее подключение не показывается включённым`() {
        // Показать «включено», пока туннель только поднимается, значило бы
        // обещать связь, которой ещё нет.
        assertEquals(
            TunnelTileState.Connecting,
            TunnelTileState.from(TunnelStateSnapshot.State.Connecting),
        )
    }

    @Test
    fun `незнание не считается выключенным состоянием`() {
        // Самое важное здесь: `null`-состояние — это «не знаем», а не «выключено».
        // Считать его выключенным значило бы показать неактивной плитку при уже
        // поднятом туннеле — ровно то, ради чего снимок и заведён.
        assertEquals(
            TunnelTileState.Unknown,
            TunnelTileState.from(TunnelStateSnapshot.State.Unknown),
        )
    }

    @Test
    fun `снятый туннель показывается выключенным`() {
        assertEquals(
            TunnelTileState.Disconnected,
            TunnelTileState.from(TunnelStateSnapshot.State.Disconnected),
        )
    }

    @Test
    fun `состояние сервиса разбирается верно`() {
        // Разбор строк SystemState — сопоставление с внутренним типом сервиса,
        // и ошибка здесь дала бы плитку, показывающую не то, что есть.
        val cases = mapOf(
            "ESTABLISHED" to TunnelStateSnapshot.State.Connected,
            "CONNECTING" to TunnelStateSnapshot.State.Connecting,
            "IDLE" to TunnelStateSnapshot.State.Disconnected,
            "LOST" to TunnelStateSnapshot.State.Disconnected,
            "FAILED" to TunnelStateSnapshot.State.Disconnected,
        )

        cases.forEach { (raw, expected) ->
            TunnelStateSnapshot.stateName = raw
            assertEquals(expected, TunnelStateSnapshot.read(), "для состояния $raw")
        }
    }

    @Test
    fun `неизвестная строка состояния даёт незнание, а не выключено`() {
        TunnelStateSnapshot.stateName = "ЧТО-ТО-НОВОЕ"

        assertEquals(TunnelStateSnapshot.State.Unknown, TunnelStateSnapshot.read())
    }

    @Test
    fun `отсутствие состояния даёт незнание`() {
        // Процесс только поднялся, сервис ещё ничего не сообщал.
        TunnelStateSnapshot.stateName = null

        assertEquals(TunnelStateSnapshot.State.Unknown, TunnelStateSnapshot.read())
    }
}
