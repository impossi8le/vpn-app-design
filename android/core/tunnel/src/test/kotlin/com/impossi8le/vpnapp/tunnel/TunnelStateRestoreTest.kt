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
}