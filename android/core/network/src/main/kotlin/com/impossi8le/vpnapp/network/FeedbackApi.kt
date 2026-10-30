package com.impossi8le.vpnapp.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Исход отправки пожелания. */
sealed interface FeedbackResult {
    /** Принято сервером. */
    data object Sent : FeedbackResult

    /** `401`: сессия истекла — текст остаётся на экране, пользователь войдёт заново. */
    data object NotAuthorized : FeedbackResult

    /** Сеть или `5xx`: сообщение не ушло, повтор возможен. */
    data class Retry(val reason: String) : FeedbackResult
}

/**
 * Отправка пожелания на `POST /api/v1/feedback`.
 *
 * Отдельный путь, а не `/report`: отчёт — это диагностика с логами, а здесь
 * пользователь пишет от себя. Смешав их, поддержка получала бы тексты в
 * отчётах об ошибках, а пожелания терялись бы среди логов.
 *
 * Тело — JSON, а не `multipart`: здесь одна строка текста, файлов нет.
 */
class FeedbackApi(private val client: ApiClient) {

    suspend fun send(text: String, appVersion: Int?): FeedbackResult =
        withContext(Dispatchers.IO) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@withContext FeedbackResult.Retry("empty")

            val body = buildJsonObject {
                put("text", trimmed.take(MAX_TEXT_CHARS))
                put("platform", PLATFORM_ANDROID)
                if (appVersion != null) put("app_version", appVersion)
            }.toString()

            try {
                val request = Request.Builder()
                    .url("${client.baseUrl}/feedback")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .apply { client.sessionToken?.let { header("Authorization", "Bearer $it") } }
                    .build()

                client.http.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 || response.code == 403 -> FeedbackResult.NotAuthorized
                        response.isSuccessful -> FeedbackResult.Sent
                        // 400 — тело негодно (например, пустое). Повтор не поможет,
                        // но и терять текст нельзя: показываем как «не отправлено».
                        else -> FeedbackResult.Retry("http_${response.code}")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                FeedbackResult.Retry(e.message ?: "network")
            }
        }

    companion object {
        /**
         * Потолок совпадает с серверным (`MAX_FEEDBACK_CHARS = 4000`).
         *
         * Режем здесь, а не только в поле ввода: вставка из буфера и будущие
         * вызывающие не должны упираться в `400` от сервера.
         */
        const val MAX_TEXT_CHARS = 4000
    }
}

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()