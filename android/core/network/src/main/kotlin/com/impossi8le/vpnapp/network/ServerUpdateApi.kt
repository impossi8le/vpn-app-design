package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.update.ReleaseInfo
import com.impossi8le.vpnapp.domain.update.UpdateError
import com.impossi8le.vpnapp.domain.update.UpdateException
import com.impossi8le.vpnapp.domain.update.UpdateService
import com.impossi8le.vpnapp.domain.update.parseMinSupported
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

/**
 * Источник версий — наш сервер, а не GitHub.
 *
 * Причина: `api.github.com` в РФ бывает недоступен, а раздача обновлений не
 * должна зависеть от чужого домена. Сервер отдаёт номер версии и ссылку на APK;
 * ссылка ведёт на ту же раздачу.
 *
 * Тег синтезируется как `android-v<номер>`, а не приходит готовым: формат тега —
 * правило домена (`parseReleaseTag`), и серверный ответ не должен заводить
 * второй формат сборки.
 */
class ServerUpdateApi(
    private val client: ApiClient,
    private val platform: String = "android",
    private val json: Json = Json { ignoreUnknownKeys = true },
) : UpdateService {

    override suspend fun minSupported(): Int? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("${client.baseUrl}/app/min-supported").get().build()
            client.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                parseMinSupported(response.body?.string().orEmpty())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Нет порога — блокировать нечем. См. isVersionSupported.
            null
        }
    }

    override suspend fun latestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${client.baseUrl}/app/latest?platform=$platform")
                .get()
                .build()

            client.http.newCall(request).execute().use { response ->
                when {
                    // «Релизов нет» и «проверить не удалось» — разные вещи: первое
                    // значит «обновляться не с чего», второе требует повтора.
                    response.code == 404 -> Result.failure(UpdateException(UpdateError.NotFound))
                    !response.isSuccessful -> Result.failure(
                        UpdateException(UpdateError.Unexpected(response.code)),
                    )
                    else -> Result.success(parseLatest(response.body?.string().orEmpty()))
                }
            }
        } catch (e: UpdateException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(UpdateException(UpdateError.NetworkUnavailable))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(UpdateException(UpdateError.Unexpected(statusCode = 0)))
        }
    }

    private fun parseLatest(body: String): ReleaseInfo {
        val root = json.parseToJsonElement(body).jsonObject
        val version = (root["version"] as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull
        val apkUrl = (root["apk_url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
        if (version == null || apkUrl.isNullOrEmpty()) {
            // Обновление без ссылки предложить нельзя: кнопка «Скачать» молчала бы.
            throw UpdateException(UpdateError.Unexpected(statusCode = 200))
        }
        return ReleaseInfo(tagName = "android-v$version", apkUrl = apkUrl)
    }
}
