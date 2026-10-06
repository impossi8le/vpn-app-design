package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.tunnel.BypassRoute
import com.impossi8le.vpnapp.domain.tunnel.BypassRoutesService
import com.impossi8le.vpnapp.domain.tunnel.parseBypassCidr
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

/**
 * Список обходов с нашего сервера.
 *
 * Любая неудача — пустой список, а не исключение: отсутствие списка не должно
 * мешать ни подключению, ни защите. Мусорные адреса пропускаются поштучно —
 * одна битая строка не отменяет рабочие.
 */
class BypassRoutesApi(
    private val client: ApiClient,
    private val platform: String = "android",
    private val json: Json = Json { ignoreUnknownKeys = true },
) : BypassRoutesService {

    override suspend fun routes(): List<BypassRoute> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${client.baseUrl}/app/bypass-routes?platform=$platform")
                .get()
                .build()
            client.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                parse(response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parse(body: String): List<BypassRoute> =
        json.parseToJsonElement(body).jsonObject["routes"]?.jsonArray.orEmpty()
            .mapNotNull { it.toString().trim('"').let(::parseBypassCidr) }
}
