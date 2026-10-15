package com.impossi8le.vpnapp.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * ЗАГЛУШКА. Полная реализация живёт в ветке Task 7 плана
 * (`2026-10-13-error-reporting-client.md`). Здесь объявлены ровно те сигнатуры,
 * которые нужны ветке D (Task 10): [ReportResult] и `send`.
 */
sealed interface ReportResult {
    /** Успех. [reportId] может быть `null`, если сервер ответил без тела. */
    data class Sent(val reportId: String?) : ReportResult

    /** `401`: сессия истекла. Отчёт сохраняем локально. */
    data object NotAuthorized : ReportResult

    /** `413`: слишком большой — предлагаем вариант без логов. */
    data object TooLarge : ReportResult

    /** Сеть, `429`, `5xx`: отчёт остаётся в локальной очереди. */
    data class Retry(val reason: String) : ReportResult
}

/**
 * Отправка отчёта на `POST /api/v1/report`.
 *
 * `multipart`, а не JSON: логи и конфиг — файлы, а base64 раздул бы тело.
 * Отсутствующие части не добавляются вовсе (пустая часть читалась бы как
 * «файл есть, но пустой»).
 */
class ReportApi(
    private val client: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    suspend fun send(
        report: String,
        logs: String?,
        config: String?,
        meta: String,
    ): ReportResult = withContext(Dispatchers.IO) {
        try {
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("report", report, report.toRequestBody(TEXT_MEDIA))
                .addFormDataPart("meta", meta, meta.toRequestBody(JSON_MEDIA))
            logs?.let { builder.addFormDataPart("logs", "logs.txt", it.toRequestBody(TEXT_MEDIA)) }
            config?.let { builder.addFormDataPart("config", "profile.ovpn", it.toRequestBody(TEXT_MEDIA)) }

            val request = Request.Builder()
                .url("${client.baseUrl}/report")
                .post(builder.build())
                .apply { client.sessionToken?.let { header("Authorization", "Bearer $it") } }
                .build()

            client.http.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> ReportResult.NotAuthorized
                    response.code == 413 -> ReportResult.TooLarge
                    response.isSuccessful -> ReportResult.Sent(parseReportId(response.body?.string()))
                    response.code == 429 -> ReportResult.Retry("rate_limited")
                    response.code in 500..599 -> ReportResult.Retry("server_error")
                    else -> ReportResult.Retry("http_${response.code}")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ReportResult.Retry(e.message ?: "network")
        }
    }

    private fun parseReportId(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["report_id"]?.jsonPrimitive?.content
        }.getOrNull()
    }
}

private val TEXT_MEDIA = "text/plain; charset=utf-8".toMediaType()
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
