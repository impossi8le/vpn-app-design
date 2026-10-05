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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request

/** Последний релиз публичного репозитория (см. §10.3 архитектуры). */
const val GITHUB_RELEASES_URL =
    "https://api.github.com/repos/impossi8le/vpn-app-design/releases/latest"

/**
 * Порог поддерживаемых версий — обычный файл в репозитории.
 *
 * Не поле релиза и не бэкенд: поднять порог должно быть видно в истории git и не
 * требовать выпуска новой сборки. `raw.githubusercontent` отдаёт файл анонимно и
 * без лимита на запросы, который есть у API.
 */
const val MIN_SUPPORTED_URL =
    "https://raw.githubusercontent.com/impossi8le/vpn-app-design/main/min-supported.txt"

/**
 * Чтение последнего релиза GitHub.
 *
 * Анонимно: релиз публичный, токен не нужен. Обратная сторона — лимит запросов
 * на IP, поэтому проверка вызывается при запуске и вручную, а не в цикле.
 */
class UpdateApi(
    private val client: ApiClient,
    private val releasesUrl: String = GITHUB_RELEASES_URL,
    private val minSupportedUrl: String = MIN_SUPPORTED_URL,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : UpdateService {

    override suspend fun minSupported(): Int? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(minSupportedUrl).get().build()
            client.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                parseMinSupported(response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            // Файл недоступен — порога нет, блокировать нечем. Запирать
            // пользователя из-за неполученной цифры нельзя (см. isVersionSupported).
            null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun latestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(releasesUrl)
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()

            client.http.newCall(request).execute().use { response ->
                when {
                    // Различать эти три случая обязательно: «релизов нет» — это
                    // «обновляться не с чего», а обрыв и лимит — «проверить не
                    // удалось». Свести их к одной ошибке значило бы показать
                    // пользователю неверную причину.
                    response.code == 404 -> Result.failure(UpdateException(UpdateError.NotFound))
                    response.code == 403 -> Result.failure(UpdateException(UpdateError.RateLimited))
                    !response.isSuccessful -> Result.failure(
                        UpdateException(UpdateError.Unexpected(response.code)),
                    )
                    else -> Result.success(parseRelease(response.body?.string().orEmpty()))
                }
            }
        } catch (e: UpdateException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(UpdateException(UpdateError.NetworkUnavailable))
        } catch (e: Exception) {
            // Битое тело ловится здесь: parseRelease бросает доменное исключение,
            // но json-парсер — своё. Оба сводим к «ответ не разобрался».
            Result.failure(UpdateException(UpdateError.Unexpected(statusCode = 0)))
        }
    }

    /**
     * Разбор ответа. Ассет ищем по расширению `.apk`: имя файла меняется от
     * сборки к сборке, а расширение — нет.
     */
    private fun parseRelease(body: String): ReleaseInfo {
        val root = json.parseToJsonElement(body).jsonObject
        val tag = root.str("tag_name").orEmpty()
        val apkUrl = root["assets"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .firstOrNull { it.str("name")?.endsWith(".apk") == true }
            ?.str("browser_download_url")
        if (tag.isEmpty() || apkUrl.isNullOrEmpty()) {
            // Обновление без ссылки на APK предложить нельзя: кнопка «Скачать»
            // не смогла бы ничего скачать.
            throw UpdateException(UpdateError.Unexpected(statusCode = 200))
        }
        return ReleaseInfo(tagName = tag, apkUrl = apkUrl)
    }
}
