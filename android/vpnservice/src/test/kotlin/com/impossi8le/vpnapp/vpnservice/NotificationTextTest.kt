package com.impossi8le.vpnapp.vpnservice

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Текст уведомления идёт от реального состояния, а не от константы.
 *
 * Раньше в шторке всегда висело «Туннель поднимается», в том числе когда
 * туннель уже поднят. Это ложное сообщение о процессе — тот же класс ошибки,
 * что и ложный зелёный статус, против которого написан §6.
 */
class NotificationTextTest {

    @Test
    fun `в покое уведомления нет`() {
        assertNull(notificationTextFor(SystemState.IDLE))
    }

    @Test
    fun `подключение говорит о процессе`() {
        assertEquals("Подключение…", notificationTextFor(SystemState.CONNECTING))
    }

    @Test
    fun `поднятый туннель говорит подключено`() {
        assertEquals("Подключено", notificationTextFor(SystemState.ESTABLISHED))
    }

    @Test
    fun `потеря связи говорит прямо`() {
        assertEquals("Соединение потеряно", notificationTextFor(SystemState.LOST))
    }

    @Test
    fun `отказ говорит об отказе`() {
        assertEquals("Не удалось подключиться", notificationTextFor(SystemState.FAILED))
    }
}
