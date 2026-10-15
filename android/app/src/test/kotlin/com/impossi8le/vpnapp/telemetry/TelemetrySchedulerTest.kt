package com.impossi8le.vpnapp.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        // Требование вайфая лишило бы данных пользователей мобильной сети, поэтому
        // проверяем именно `Connected`: `assertEquals` уже отличает его от
        // `Unmetered`, и отдельный `assertFalse` на то же самое был бы тавтологией.
        assertEquals(
            NetworkRequirement.Connected,
            TelemetryScheduler.NETWORK_REQUIREMENT,
            "требовать UNMETERED нельзя: данные не пришли бы никогда",
        )
    }

    @Test
    fun `батарея не должна быть низкой`() {
        assertTrue(TelemetryScheduler.REQUIRES_BATTERY_NOT_LOW)
    }

    @Test
    fun `период — шесть часов`() {
        // Не «положительное число», а конкретное значение: период — часть
        // контракта с сервером, и молчаливое изменение (например, на 15 минут)
        // участило бы отправку без решения человека.
        assertEquals(6L * 60, TelemetryScheduler.INTERVAL_MINUTES)
    }
}
