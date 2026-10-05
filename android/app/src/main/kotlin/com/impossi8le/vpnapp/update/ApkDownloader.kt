package com.impossi8le.vpnapp.update

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val APK_NAME = "update.apk"
private const val PART_SUFFIX = ".part"

/**
 * Скачивание APK обновления во внутренний кеш.
 *
 * Пишем сначала в `.part` и лишь по завершении переименовываем в `.apk`:
 * оборванная закачка не должна оставить файл, который установщик примет за
 * готовый. [clearStale] убирает такие огрызки при следующем запуске.
 */
class ApkDownloader(
    private val http: OkHttpClient,
    private val cacheDir: File,
) {

    suspend fun download(url: String, onProgress: (Int) -> Unit = {}): Result<File> =
        withContext(Dispatchers.IO) {
            val target = File(cacheDir, APK_NAME)
            val part = File(cacheDir, APK_NAME + PART_SUFFIX)
            var downloaded = 0L
            try {
                val request = Request.Builder().url(url).get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            IOException("сервер отдал ${response.code}"),
                        )
                    }
                    val total = response.body?.contentLength() ?: -1L
                    response.body?.byteStream()?.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (total > 0) {
                                    onProgress(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                                }
                            }
                        }
                    }
                }

                // Пустой файл — это не «готово»: установщик скажет «пакет повреждён»,
                // а пользователь не поймёт, откуда. Честнее сказать «не скачалось».
                if (!part.exists() || part.length() == 0L) {
                    part.delete()
                    return@withContext Result.failure(IOException("файл скачался пустым"))
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.delete()
                    return@withContext Result.failure(IOException("не удалось сохранить файл"))
                }
                onProgress(100)
                Result.success(target)
            } catch (e: IOException) {
                part.delete()
                Result.failure(e)
            }
        }

    /** Убрать недокачанные файлы прошлых попыток. */
    fun clearStale() {
        cacheDir.listFiles()
            ?.filter { it.name.endsWith(PART_SUFFIX) }
            ?.forEach { it.delete() }
    }
}
