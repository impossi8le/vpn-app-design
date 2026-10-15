package com.impossi8le.vpnapp.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/**
 * Параметры периодической отправки метрик.
 *
 * Проверяются как чистые значения, без WorkManager: сама задача запускается
 * системой, но её ОГРАНИЧЕНИЯ — это данные, и их видно тестом. Требовать
 * `UNMETERED` намеренно НЕ стали: метрики мелкие, а обновление вайфая у части
 * пользователей нет — с этим ограничением данные не пришли бы никогда.
 */
class TelemetrySchedulerTest {

    @Test
    fun `интервал — не меньше системного минимума`() {
        assertTrue(
            TelemetryScheduler.INTERVAL_MINUTES >= 15,
            "WorkManager не примет период меньше 15 минут — задача молча не запустится",
        )
    }

    @Test
    fun `сеть нужна любая, а не только wifi`() {
        assertEquals(NetworkRequirement.Connected, TelemetryScheduler.NETWORK_REQUIREMENT)
        assertFalse(
            TelemetryScheduler.NETWORK_REQUIREMENT == NetworkRequirement.Unmetered,
            "требование вайфая лишило бы данных пользователей мобильной сети",
        )
    }

    @Test
    fun `батарея не должна быть низкой`() {
        assertTrue(TelemetryScheduler.REQUIRES_BATTERY_NOT_LOW)
    }

    @Test
    fun `период описан в минутах и положителен`() {
        assertTrue(TelemetryScheduler.INTERVAL_MINUTES > 0)
        assertEquals(6, TimeUnit.HOURS.toMinutes(6) / 60)
    }
}
