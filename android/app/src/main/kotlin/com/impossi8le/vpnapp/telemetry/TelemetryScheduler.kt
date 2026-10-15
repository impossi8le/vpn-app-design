package com.impossi8le.vpnapp.telemetry

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Требование к сети — отдельным перечислением, чтобы не тянуть `Constraints` в
 * JVM-тест.
 */
enum class NetworkRequirement { Connected, Unmetered }

/**
 * Постановка периодической отправки метрик.
 *
 * Ограничения выбраны так: любая сеть (метрики мелкие, а вайфай есть не у всех —
 * с `UNMETERED` данные не пришли бы вовсе), батарея не ниже порога,
 * экспоненциальный backoff при неудаче. Работает и когда приложение закрыто:
 * задача переживает перезапуск, иначе очередь копилась бы вечно.
 */
object TelemetryScheduler {

    const val INTERVAL_MINUTES = 6L * 60
    const val REQUIRES_BATTERY_NOT_LOW = true
    val NETWORK_REQUIREMENT = NetworkRequirement.Connected

    private const val UNIQUE_NAME = "com.impossi8le.vpnapp.telemetry"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(REQUIRES_BATTERY_NOT_LOW)
            .build()

        val request = PeriodicWorkRequestBuilder<TelemetryWorker>(
            INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        // KEEP, а не REPLACE: перезапуск приложения не должен сбрасывать таймер
        // и переносить отправку на новый полный интервал.
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
