package com.impossi8le.vpnapp.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Перевод HTTP-ответа в типизированную ошибку.
 *
 * Маппинг ведётся сначала по машиночитаемому `code` из тела, и только потом по
 * статусу: контракт переиспользует `403` для трёх разных ситуаций
 * (`account_blocked`, `config_revoked`, `subscription_expired`), и по одному
 * статусу их не различить.
 */
object ErrorMapping {

    private val json = Json { ignoreUnknownKeys = true }

    fun fromStatus(statusCode: Int, body: String): ApiError =
        fromCode(parseCode(body), statusCode, body)

    fun fromCode(code: String?, statusCode: Int, body: String = ""): ApiError = when {
        // 5xx — сбой сервера или его апстрима, а НЕ решение о доступе.
        //
        // Проверка стоит ДО разбора `code`, потому что сервер отдаёт временную
        // недоступность генератора как 503 с кодом `config_retired`. По коду это
        // читалось как приговор, и экран писал «Доступ к подключению отозван»
        // при живом профиле сроком до 2027 года (фото и серверный лог совпали:
        // control-api был недоступен). Приговор по 5xx не выносим никогда:
        // отозванность — это 403/410 с явным кодом, и только она.
        statusCode in 500..599 -> ApiError.Unexpected(statusCode, body)
        code == "nonce_mismatch" -> ApiError.NonceMismatch
        code == "invalid_secret" -> ApiError.InvalidSecret
        code == "unauthorized" || statusCode == 401 -> ApiError.Unauthorized
        code == "account_blocked" -> ApiError.AccountBlocked
        code == "config_revoked" || code == "config_retired" -> ApiError.ConfigRevoked
        code == "subscription_expired" -> ApiError.SubscriptionExpired
        code == "config_not_found" || code == "operation_not_found" -> ApiError.NotFound
        code == "rate_limited" || statusCode == 429 -> ApiError.RateLimited
        code == "public_code_conflict" || code == "already_consumed" -> ApiError.Conflict(code!!)
        statusCode == 404 -> ApiError.NotFound
        statusCode == 410 -> ApiError.ConfigRevoked
        else -> ApiError.Unexpected(statusCode, body)
    }

    fun parseErrorBody(body: String): ApiErrorBody? {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            val error = root["error"]?.jsonObject
            val code = error?.get("code")?.jsonPrimitive?.content
            if (code == null) {
                null
            } else {
                ApiErrorBody(
                    code = code,
                    message = error["message"]?.jsonPrimitive?.content,
                    retryable = error["retryable"]?.jsonPrimitive?.booleanOrNull,
                )
            }
        } catch (_: Exception) {
            // Тело может быть пустым или не JSON — это не повод падать.
            null
        }
    }

    private fun parseCode(body: String): String? = parseErrorBody(body)?.code
}
