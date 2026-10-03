package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.auth.AuthService
import com.impossi8le.vpnapp.domain.auth.LoginChallenge
import com.impossi8le.vpnapp.domain.auth.PollOutcome
import com.impossi8le.vpnapp.domain.auth.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Реализация контракта §1–2.
 *
 * `startLogin` генерирует `secret` здесь же и оставляет его на устройстве:
 * на сервер уходит только `secret_hash`. Возвращаемый `LoginChallenge` несёт
 * секрет вызывающему, чтобы тот мог предъявить его при опросе.
 */
class AuthApi(
    private val client: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AuthService {

    override suspend fun startLogin(deviceName: String): Result<LoginChallenge> =
        withContext(Dispatchers.IO) {
            runCatching {
                val publicCode = Crypto.newPublicCode()
                val secret = Crypto.newSecret()

                val payload = buildJsonObject {
                    put("public_code", JsonPrimitive(publicCode))
                    put("secret_hash", JsonPrimitive("sha256:${Crypto.sha256Hex(secret)}"))
                    put("device_name", JsonPrimitive(deviceName))
                    put("platform", JsonPrimitive(PLATFORM_ANDROID))
                    put("app_version", JsonPrimitive(APP_VERSION))
                }

                val request = Request.Builder()
                    .url("${client.baseUrl}/auth/link")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()

                client.http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@runCatching Result.failure(ApiException(ErrorMapping.fromStatus(response.code, body)))
                    }

                    val root = json.parseToJsonElement(body).jsonObject
                    LoginChallenge(
                        publicCode = root.string("public_code") ?: publicCode,
                        secret = secret,
                        deepLink = root.string("deep_link") ?: "",
                        expiresAtEpochSeconds = root.instant("expires_at"),
                    )
                }
            }.fold(
                onSuccess = { Result.success(it) },
                // Сетевые сбои — тоже типизированная ошибка, а не сырое исключение.
                onFailure = { Result.failure(if (it is ApiException) it else ApiException(ApiError.Network(it.message ?: "сеть недоступна"))) },
            )
        }

    override suspend fun pollSession(
        publicCode: String,
        secret: String,
        deviceNonce: String,
    ): Result<PollOutcome> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = buildJsonObject {
                put("public_code", JsonPrimitive(publicCode))
                put("secret", JsonPrimitive(secret))
                put("device_nonce", JsonPrimitive(deviceNonce))
            }

            val request = Request.Builder()
                .url("${client.baseUrl}/auth/poll")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            client.http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    val error = ErrorMapping.fromStatus(response.code, body)
                    // 409 already_consumed — не тупик: клиент предложит войти заново.
                    if (error is ApiError.Conflict && error.code == "already_consumed") {
                        return@runCatching PollOutcome.AlreadyConsumed
                    }
                    return@runCatching Result.failure(ApiException(error))
                }

                val root = json.parseToJsonElement(body).jsonObject
                when (root.string("status")) {
                    "confirmed" -> PollOutcome.Confirmed(
                        session = Session(
                            token = root.string("session_token").orEmpty(),
                            expiresAtEpochSeconds = root.instant("expires_at"),
                        ),
                        chatId = root.long("chat_id") ?: 0L,
                    )
                    "pending" -> PollOutcome.Pending(root.long("retry_after_ms") ?: 2000L)
                    "expired" -> PollOutcome.Expired
                    "denied" -> PollOutcome.Denied
                    "attempt_limit_exceeded" -> PollOutcome.AttemptLimitExceeded
                    else -> Result.failure(ApiException(ApiError.Unexpected(response.code, body)))
                }
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(if (it is ApiException) it else ApiException(ApiError.Network(it.message ?: "сеть недоступна"))) },
        )
    }

    private companion object {
        const val APP_VERSION = "1.0.0"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

/** Исключение-обёртка: несёт типизированную ошибку через `Result`. */
class ApiException(val error: ApiError) : IOException(error.toString())
