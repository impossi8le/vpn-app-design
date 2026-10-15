package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.diagnostics.TelemetryEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * ЗАГЛУШКА. Полная реализация живёт в ветке Task 6 плана
 * (`2026-10-13-error-reporting-client.md`). Здесь объявлены ровно те сигнатуры,
 * которые нужны ветке D (Task 9): [TelemetryResult] и `send`.
 *
 * Различение исходов обязательно: `401` — выбросить батч, `4xx` (кроме 401/403)
 * — повтор бессмыслен, `429`/`5xx` — повторить позже. Слить их в одно «не
 * получилось» значило бы либо терять данные, либо бесконечно долбить сервер.
 */
sealed interface TelemetryResult {
    /** `204`: принято, из очереди можно удалять. */
    data object Accepted : TelemetryResult

    /** `401`: токена нет — очередь чистим, слать нечего. */
    data object NotAuthorized : TelemetryResult

    /** `4xx`: тело не принято, повтор не поможет — батч отбрасываем. */
    data object Rejected : TelemetryResult

    /** `429`, `5xx`, сеть: оставляем в очереди и повторяем. */
    data object Retry : TelemetryResult
}

/**
 * Отправка метрик на `POST /api/v1/telemetry/events`.
 *
 * Контракт: батч в одну сторону, ответ `204` — всё, что клиенту нужно. Тело
 * ответа не читается вовсе: разбирать нечего, а ошибка разбора не должна
 * превращать успешную отправку в неудачную.
 */
class TelemetryApi(
    private val client: ApiClient,
    private val platform: String = PLATFORM_ANDROID,
    private val json: Json = Json { encodeDefaults = false },
) {

    suspend fun send(appVersion: Int, events: List<TelemetryEvent>): TelemetryResult =
        withContext(Dispatchers.IO) {
            try {
                val payload = buildJsonObject {
                    put("app_version", JsonPrimitive(appVersion))
                    put("platform", JsonPrimitive(platform))
                    put(
                        "events",
                        json.encodeToJsonElement(ListSerializer(TelemetryEvent.serializer()), events),
                    )
                }
                val request = Request.Builder()
                    .url("${client.baseUrl}/telemetry/events")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .apply { client.sessionToken?.let { header("Authorization", "Bearer $it") } }
                    .build()
                client.http.newCall(request).execute().use { response ->
                    when (response.code) {
                        204, 200, 202 -> TelemetryResult.Accepted
                        401, 403 -> TelemetryResult.NotAuthorized
                        in 400..499 -> TelemetryResult.Rejected
                        else -> TelemetryResult.Retry
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                TelemetryResult.Retry
            }
        }
}

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
