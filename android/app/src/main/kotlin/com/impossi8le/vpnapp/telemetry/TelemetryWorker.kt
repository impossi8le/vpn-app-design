package com.impossi8le.vpnapp.telemetry

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.impossi8le.vpnapp.domain.diagnostics.TelemetryEvent
import com.impossi8le.vpnapp.network.TelemetryResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Фоновая отправка накопленных метрик.
 *
 * Разбор исхода — по [TelemetryResult]: принятое удаляем, «повторить» оставляем,
 * отвергнутое телом (`4xx`, кроме 401/403/429) выбрасываем — иначе негодный батч
 * душил бы очередь навсегда. При `401` очередь чистится: без токена отправить
 * нечего, а копить чужие события после выхода из аккаунта нельзя.
 */
class TelemetryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = TelemetryGraph.from(applicationContext)
        val queue = graph.queue
        val pending = queue.peek()
        if (pending.isEmpty()) return Result.success()

        val events = pending.mapNotNull { parse(it) }
        if (events.isEmpty()) {
            queue.remove(pending.mapNotNull(::eventIdOf))
            return Result.success()
        }

        return when (graph.api.send(appVersion = graph.appVersion(), events = events)) {
            TelemetryResult.Accepted -> {
                queue.remove(events.map { it.eventId })
                Result.success()
            }

            TelemetryResult.NotAuthorized -> {
                queue.clear()
                Result.success()
            }

            TelemetryResult.Rejected -> {
                queue.remove(events.map { it.eventId })
                Result.success()
            }

            TelemetryResult.Retry -> Result.retry()
        }
    }

    private fun parse(json: String): TelemetryEvent? =
        runCatching {
            Json.decodeFromString(TelemetryEvent.serializer(), json)
        }.getOrNull()

    private fun eventIdOf(json: String): String? =
        runCatching {
            Json.parseToJsonElement(json).jsonObject["event_id"]?.jsonPrimitive?.content
        }.getOrNull()
}
