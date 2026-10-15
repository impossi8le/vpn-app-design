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

/** Исход отправки отчёта. */
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
 * `multipart`, а не JSON: логи и конфиг — файлы, а base64 раздул бы тело на треть
 * без выгоды. Отсутствующие части не добавляются вовсе (пустая часть читалась бы
 * как «файл есть, но пустой»).
 *
 * `report_id` парсится защищённо: `runCatching` вокруг разбора, потому что
 * кривое тело ответа не должно превращать доставленный отчёт в недоставленный.
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
                .addFormDataPart("report", report, report.toRequestBody(REPORT_TEXT_MEDIA))
                .addFormDataPart("meta", meta, meta.toRequestBody(REPORT_JSON_MEDIA))
            logs?.let { builder.addFormDataPart("logs", "logs.txt", it.toRequestBody(REPORT_TEXT_MEDIA)) }
            config?.let { builder.addFormDataPart("config", "profile.ovpn", it.toRequestBody(REPORT_TEXT_MEDIA)) }

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
                    // Прочие 4xx — тело негодно, повтор не поможет; показываем как
                    // «не отправлено» и оставляем в очереди, чтобы не потерять.
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

private val REPORT_TEXT_MEDIA = "text/plain; charset=utf-8".toMediaType()
private val REPORT_JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
