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

    fun parseErrorBody(body: String): ApiErrorBody? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val error = root["error"]?.jsonObject ?: return null
        ApiErrorBody(
            code = error["code"]?.jsonPrimitive?.content ?: return null,
            message = error["message"]?.jsonPrimitive?.content,
            retryable = error["retryable"]?.jsonPrimitive?.booleanOrNull,
        )
    } catch (_: Exception) {
        // Тело может быть пустым или не JSON — это не повод падать.
        null
    }

    private fun parseCode(body: String): String? = parseErrorBody(body)?.code
}
