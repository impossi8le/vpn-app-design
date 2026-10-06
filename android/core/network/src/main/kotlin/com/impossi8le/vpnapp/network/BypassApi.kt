package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.tunnel.BypassCatalog
import com.impossi8le.vpnapp.domain.tunnel.BypassControl
import com.impossi8le.vpnapp.domain.tunnel.BypassResolve
import com.impossi8le.vpnapp.domain.tunnel.BypassRoute
import com.impossi8le.vpnapp.domain.tunnel.BypassService
import com.impossi8le.vpnapp.domain.tunnel.BypassWrite
import com.impossi8le.vpnapp.domain.tunnel.label
import com.impossi8le.vpnapp.domain.tunnel.parseBypassCidr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Управление обходами с экрана «Обходы»: каталог, разбор адреса, запись списка.
 *
 * Отличие от [BypassRoutesApi] намеренное: тот читает список ради подключения и
 * на любой неудаче отдаёт пустой список, потому что отличать «нет обходов» от
 * «сервер молчит» вызывающему нечего. Здесь экран обязан это различать (§6),
 * поэтому чтение отдаёт `null` при сбое, а записи — типизированный исход.
 *
 * Стиль повторяет [BypassRoutesApi]: OkHttp через `client.http`, работа на
 * `Dispatchers.IO`, `CancellationException` пробрасывается ДО общего `catch`,
 * а неудача деградирует в типизированный исход, а не в исключение.
 */
class BypassApi(
    private val client: ApiClient,
    private val platform: String = PLATFORM_ANDROID,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : BypassControl {

    override suspend fun fetchRoutes(): List<BypassRoute>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${client.baseUrl}/app/bypass-routes?platform=$platform")
                .get()
                .build()
            client.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                parseRoutes(response.body?.string().orEmpty())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun catalog(): BypassCatalog = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${client.baseUrl}/app/bypass-services?platform=$platform")
                .get()
                .build()
            client.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext BypassCatalog.Failed
                val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                val services = root["services"]?.jsonArray.orEmpty().map { element ->
                    val item = element.jsonObject
                    BypassService(
                        key = item.str("key").orEmpty(),
                        title = item.str("title").orEmpty(),
                        domains = item["domains"]?.jsonArray.orEmpty().mapNotNull { it.stringOrNull() },
                    )
                }
                BypassCatalog.Loaded(services)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BypassCatalog.Failed
        }
    }

    override suspend fun resolve(targets: List<String>): BypassResolve = withContext(Dispatchers.IO) {
        try {
            val payload = buildJsonObject {
                put("targets", JsonArray(targets.map { JsonPrimitive(it) }))
            }
            val request = Request.Builder()
                .url("${client.baseUrl}/app/bypass/resolve")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.http.newCall(request).execute().use { response ->
                when {
                    // 400 invalid_target: адрес неверный ИЛИ слишком широкий. Отдельный
                    // исход — экран скажет «перепишите адрес», а не «повторите».
                    response.code == 400 -> BypassResolve.InvalidTarget
                    !response.isSuccessful -> BypassResolve.Failed
                    else -> BypassResolve.Resolved(parseRoutes(response.body?.string().orEmpty()))
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BypassResolve.Failed
        }
    }

    override suspend fun set(routes: List<BypassRoute>): BypassWrite = withContext(Dispatchers.IO) {
        try {
            val payload = buildJsonObject {
                put("routes", JsonArray(routes.map { JsonPrimitive(it.label()) }))
            }
            val request = Request.Builder()
                .url("${client.baseUrl}/app/bypass/set")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    // Сервер отвергает ВЕСЬ запрос из-за одной плохой записи —
                    // значит исхода два: записано всё или не записано ничего.
                    response.code == 400 -> BypassWrite.Invalid
                    !response.isSuccessful -> BypassWrite.Failed
                    else -> BypassWrite.Applied(
                        json.parseToJsonElement(body).jsonObject["count"]
                            ?.jsonPrimitive?.longOrNull?.toInt() ?: routes.size,
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BypassWrite.Failed
        }
    }

    private fun parseRoutes(body: String): List<BypassRoute> =
        json.parseToJsonElement(body).jsonObject["routes"]?.jsonArray.orEmpty()
            .mapNotNull { it.stringOrNull()?.let(::parseBypassCidr) }
}

/**
 * Строковое значение элемента JSON или `null`.
 *
 * `jsonPrimitive` доступен только если элемент — примитив; на объекте он бросает.
 * Одна кривая запись в ответе не должна ронять весь разбор.
 */
private fun JsonElement.stringOrNull(): String? =
    runCatching { jsonPrimitive.content }.getOrNull()

private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
