package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.config.ConfigList
import com.impossi8le.vpnapp.domain.config.ConfigFetchError
import com.impossi8le.vpnapp.domain.config.ConfigFetchException
import com.impossi8le.vpnapp.domain.config.ConfigService
import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.FetchedConfig
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import java.io.IOException

/**
 * Контракт §3–4.
 *
 * `/config/{id}` отдаёт СЫРОЙ `.ovpn` с заголовками версии и хеша, а не JSON.
 * Версия нужна, чтобы не перезаписывать профиль, если у клиента уже эта версия;
 * хеш — чтобы проверить, что записалось именно то, что пришло.
 */
class ConfigApi(
    private val client: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ConfigService {

    override suspend fun listConfigs(): Result<ConfigList> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${client.baseUrl}/me")
                .applyAuth()
                .get()
                .build()

            client.http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        ApiException(ErrorMapping.fromStatus(response.code, body)),
                    )
                }

                val root = json.parseToJsonElement(body).jsonObject
                Result.success(
                    ConfigList(
                        chatId = root.long("chat_id") ?: 0L,
                        configs = root["configs"]?.jsonArray.orEmpty().map { element ->
                            val item = element.jsonObject
                            val location = item["location"]?.jsonObject
                            ConfigSummary(
                                id = item.str("id").orEmpty(),
                                name = item.str("name").orEmpty(),
                                countryCode = location?.str("country_code").orEmpty(),
                                city = location?.str("city").orEmpty(),
                                startDateEpochSeconds = item.instant("start_date"),
                                endDateEpochSeconds = item.instant("end_date"),
                                status = parseStatus(item.str("status")),
                            )
                        },
                    ),
                )
            }
        } catch (e: ApiException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(ApiException(ApiError.Network(e.message ?: "сеть недоступна")))
        } catch (e: Exception) {
            // Битое тело ответа: не падаем, а сообщаем понятную причину.
            Result.failure(ApiException(ApiError.Network(e.message ?: "неожиданный сбой")))
        }
    }

    override suspend fun fetchConfig(configId: String): Result<FetchedConfig> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${client.baseUrl}/config/$configId")
                .applyAuth()
                .get()
                .build()

            client.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    // Ошибка переводится в доменный тип: вызывающий обязан
                    // различить истёкшую подписку, отозванный конфиг и обрыв сети.
                    return@withContext Result.failure(
                        ConfigFetchException(
                            ErrorMapping.fromStatus(response.code, body).toConfigFetchError(),
                        ),
                    )
                }

                val bytes = response.body?.bytes()
                    ?: return@withContext Result.failure(
                        ConfigFetchException(ConfigFetchError.Unexpected(response.code)),
                    )

                Result.success(
                    FetchedConfig(
                        raw = bytes,
                        version = response.header("X-Config-Version").orEmpty(),
                        hash = response.header("X-Config-Hash").orEmpty(),
                    ),
                )
            }
        } catch (e: ConfigFetchException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(ConfigFetchException(ConfigFetchError.NetworkUnavailable))
        } catch (e: Exception) {
            Result.failure(ConfigFetchException(ConfigFetchError.Unexpected(statusCode = 0)))
        }
    }

    private fun Request.Builder.applyAuth(): Request.Builder {
        val token = client.sessionToken
        if (token != null) header("Authorization", "Bearer $token")
        return this
    }

    private fun parseStatus(raw: String?): SubscriptionStatus = when (raw) {
        "active" -> SubscriptionStatus.ACTIVE
        "expired" -> SubscriptionStatus.EXPIRED
        "revoked" -> SubscriptionStatus.REVOKED
        "pending" -> SubscriptionStatus.PENDING
        // Неизвестный статус трактуем как «не активен»: показать рабочее
        // подключение там, где сервер прислал непонятное, опаснее.
        else -> SubscriptionStatus.EXPIRED
    }
}
