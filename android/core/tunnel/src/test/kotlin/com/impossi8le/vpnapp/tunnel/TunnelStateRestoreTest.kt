package com.impossi8le.vpnapp.tunnel

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.tunnel.TunnelState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Восстановление состояния экрана из уже опубликованного (issue #57).
 *
 * Дефект на устройстве: туннель работает 12 часов, приложение выгружают из
 * недавних и запускают заново — экран говорит «не подключено», потому что
 * новый контроллер начинал с `Disconnected` и ждал широковещания, которого
 * уже не будет.
 */
class TunnelStateRestoreTest {

    @Test
    fun `поднятый туннель восстанавливается как «не проверено», а не зелёным`() {
        assertEquals(
            ConnectionStatus.VerifyingProtection,
            TunnelState.Established.toConnectionStatus(),
            "восстановленное состояние описывает интерфейс, а не замер: §6",
        )
    }

    @Test
    fun `опущенный туннель восстанавливается как «отключено»`() {
        assertEquals(
            ConnectionStatus.Disconnected,
            TunnelState.Idle.toConnectionStatus(),
        )
    }

    @Test
    fun `идёт подъём — состояние не теряется`() {
        assertEquals(
            ConnectionStatus.Connecting,
            TunnelState.Connecting.toConnectionStatus(),
        )
    }

    @Test
    fun `восстановление после обрыва остаётся восстановлением`() {
        assertEquals(
            ConnectionStatus.Recovering,
            TunnelState.Recovering.toConnectionStatus(),
        )
    }

    @Test
    fun `незнание не превращается в «отключено»`() {
        assertNull(
            TunnelState.Unknown.toConnectionStatus(),
            "сервис ещё ничего не сообщал — это не сведения о туннеле",
        )
    }

    @Test
    fun `восстановленное «поднят» без туннеля над трафиком показывается как «подключение»`() {
        val restored = TunnelState.Established.toConnectionStatus()!!
        assertEquals(
            ConnectionStatus.Connecting,
            restored.confirmedByTunnelPresence(TunnelPresence.ABSENT),
            "после перезапуска экрана порог §6 обязан действовать так же, как у события сервиса",
        )
    }

    @Test
    fun `устаревший «поднят» при отсутствии туннеля на холодном старте — «отключено»`() {
        // Сервис умер, не сообщив об этом; в хранилище остался «поднят».
        // Показывать «подключение» здесь значит держать экран в вечном ожидании.
        assertEquals(
            ConnectionStatus.Disconnected,
            ConnectionStatus.VerifyingProtection.restoredFromStore(TunnelPresence.ABSENT),
            "нет туннеля в системе — нет и подключения, снимок устарел",
        )
    }

    @Test
    fun `поднятый туннель на месте — восстановленный статус остаётся`() {
        assertEquals(
            ConnectionStatus.VerifyingProtection,
            ConnectionStatus.VerifyingProtection.restoredFromStore(TunnelPresence.PRESENT),
        )
    }

    @Test
    fun `незнание при восстановлении не гасит статус`() {
        assertEquals(
            ConnectionStatus.VerifyingProtection,
            ConnectionStatus.VerifyingProtection.restoredFromStore(TunnelPresence.UNKNOWN),
            "сбой опроса — не доказательство отсутствия туннеля",
        )
    }

    @Test
    fun `подъём не превращается в «отключено» из-за отсутствия туннеля`() {
        assertEquals(
            ConnectionStatus.Connecting,
            ConnectionStatus.Connecting.restoredFromStore(TunnelPresence.ABSENT),
            "интерфейса ещё нет по определению — это нормальный ход подключения",
        )
    }
}