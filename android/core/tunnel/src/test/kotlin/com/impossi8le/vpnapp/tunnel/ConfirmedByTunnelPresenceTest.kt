package com.impossi8le.vpnapp.tunnel

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * «Подключено» подтверждается фактом, а не событием сервиса.
 *
 * Измерено на устройстве (issue #32): сервис присылает `ESTABLISHED` на
 * **11 секунд** раньше, чем система начинает пропускать трафик через туннель.
 * Всё это время экран говорил «Подключено», а внешний адрес оставался адресом
 * провайдера — для средства обхода блокировок это утечка, о которой человек
 * не знает.
 */
class ConfirmedByTunnelPresenceTest {

    private val established = ConnectionStatus.VerifyingProtection

    @Test
    fun `без туннеля над трафиком «подключено» не показывается`() {
        // Главный случай дефекта: сервис сказал ESTABLISHED, а активная сеть —
        // не VPN. Честное состояние — «подключение», а не «подключено».
        assertEquals(
            ConnectionStatus.Connecting,
            established.confirmedByTunnelPresence(TunnelPresence.ABSENT),
            "трафик идёт мимо туннеля — говорить «Подключено» нельзя",
        )
    }

    @Test
    fun `подтверждённый туннель оставляет состояние как есть`() {
        assertEquals(
            established,
            established.confirmedByTunnelPresence(TunnelPresence.PRESENT),
        )
    }

    @Test
    fun `неизвестность не понижает состояние`() {
        // `UNKNOWN` — «спросить не удалось», а не «туннеля нет». Понижать по нему
        // значило бы рубить исправное соединение из-за случайного сбоя опроса.
        assertEquals(
            established,
            established.confirmedByTunnelPresence(TunnelPresence.UNKNOWN),
            "сбой опроса — не доказательство отсутствия туннеля",
        )
    }

    @Test
    fun `состояния без поднятого туннеля не трогаются`() {
        // Проверка касается только «туннель поднят»: у остальных своя подача,
        // и подменять их на «подключение» значило бы врать в другую сторону.
        val untouched = listOf(
            ConnectionStatus.Disconnected,
            ConnectionStatus.Connecting,
            ConnectionStatus.Recovering,
            ConnectionStatus.Failed("нет сети"),
        )

        untouched.forEach { status ->
            TunnelPresence.entries.forEach { presence ->
                assertEquals(
                    status,
                    status.confirmedByTunnelPresence(presence),
                    "$status при $presence не должен меняться",
                )
            }
        }
    }

    @Test
    fun `подтверждённая защита тоже требует факта туннеля`() {
        // `Protected` приходит только от замера, но если туннель исчез после
        // замера, показывать «подключено» всё равно нельзя: замер описывал
        // прежнее состояние.
        val protected = ConnectionStatus.ProtectionFailed(
            com.impossi8le.vpnapp.domain.protection.ProtectionVerdict
                .evaluate(false, true, true) as com.impossi8le.vpnapp.domain.protection.ProtectionVerdict.Failed,
        )

        assertEquals(
            ConnectionStatus.Connecting,
            protected.confirmedByTunnelPresence(TunnelPresence.ABSENT),
        )
    }
}
