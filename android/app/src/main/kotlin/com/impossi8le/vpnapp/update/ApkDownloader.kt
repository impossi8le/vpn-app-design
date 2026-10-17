package com.impossi8le.vpnapp.update

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

private const val APK_NAME = "update.apk"
private const val PART_SUFFIX = ".part"
private const val ETAG_SUFFIX = ".etag"
private const val BUFFER = 8192

/**
 * Скачивание APK обновления во внутренний кеш — с докачкой после обрыва.
 *
 * **Почему докачка, а не закачка с нуля.** APK — около 106 МБ. На медленном
 * канале он не успевает скачаться за один сеанс, а раньше оборванная попытка
 * начиналась заново: пользователь не мог обновиться вообще, не держа приложение
 * открытым на быстрой сети.
 *
 * **Файл привязан к версии.** В кеше лежат `update-<N>.apk.part` и
 * `update-<N>.apk.etag`. Огрызок версии 7 не подставляется в закачку версии 8:
 * склейка кусков разных сборок дала бы файл, который установщик отвергнет как
 * повреждённый, и причина была бы не видна.
 *
 * **Тело пишется сразу в целевой огрызок.** Отдельный временный файл с
 * последующим переименованием стоил бы лишнего полного копирования 106 МБ.
 * Огрызок сам по себе безопасен: установщику отдаётся только `.apk`, который
 * появляется лишь при успешном завершении.
 *
 * [clearStale] убирает огрызки чужих версий, но берегёт огрызок текущей —
 * его и нужно докачивать.
 */
class ApkDownloader(
    private val http: OkHttpClient,
    private val cacheDir: File,
) {

    /**
     * Скачать APK версии [versionCode], продолжив прошлую попытку, если она была.
     *
     * @param onProgress процент от ПОЛНОГО размера файла. При докачке отсчёт
     *   начинается с уже скачанного, а не с нуля: строка «0%» после обрыва на
     *   девяноста выглядела бы как потеря прогресса.
     */
    suspend fun download(
        url: String,
        versionCode: Int,
        onProgress: (Int) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = File(cacheDir, "update-$versionCode.apk")
        val part = partFile(cacheDir, versionCode)
        val etag = etagFile(cacheDir, versionCode)

        try {
            // Огрызок без метки бесполезен: неизвестно, та ли это сборка. Без
            // метки начинаем заново, а не рискуем склеить чужое.
            val resumable = part.exists() && etag.exists() && part.length() > 0
            var already = if (resumable) part.length() else 0L
            if (!resumable && part.exists()) part.delete()

            val response = open(url, already, if (resumable) etag.readText().trim() else null)

            response.use { res ->
                if (!res.isSuccessful) {
                    return@withContext Result.failure(IOException("сервер отдал ${res.code}"))
                }

                // 206 — сервер принял Range и отдаёт остаток; пишем в хвост
                // огрызка. 200 на запрос с Range значит, что докачка не
                // поддержана и файл пришёл целиком: дописать его к огрызку
                // нельзя, поэтому начинаем заново. Проверено: CDN GitHub
                // отвечает 206 и `Accept-Ranges: bytes`.
                val appending = res.code == 206 && already > 0
                if (!appending) already = 0L

                val remaining = res.body?.contentLength() ?: -1L
                val total = if (remaining >= 0 && appending) already + remaining else remaining

                res.body?.byteStream()?.use { input ->
                    // append = true при докачке: обычный outputStream() обрезал бы
                    // файл до нуля и записал только остаток — тест «скачано 200 из
                    // 300» ловил ровно это.
                    java.io.FileOutputStream(part, appending).use { output ->
                        val buffer = ByteArray(BUFFER)
                        var written = already
                        while (true) {
                            // Отмена воркера приходит как CancellationException:
                            // без этой проверки файл и прогресс потеряются.
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            if (total > 0) {
                                onProgress(((written * 100) / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                }

                // Метку храним рядом с огрызком: по ней следующая попытка поймёт,
                // что релиз не подменился, и продолжит с того же места.
                res.header("ETag")?.let { etag.writeText(it) }

                // Пустой файл — это не «готово»: установщик скажет «пакет
                // повреждён», а пользователь не поймёт, откуда. Честнее сказать
                // «не скачалось».
                if (!part.exists() || part.length() == 0L) {
                    part.delete()
                    return@withContext Result.failure(IOException("файл скачался пустым"))
                }

                // Неполный файл не должен выглядеть готовым: ожидаемый размер
                // известен из Content-Length, и если скачано меньше — это обрыв,
                // а не успех.
                if (total > 0 && part.length() < total) {
                    return@withContext Result.failure(
                        IOException("скачано ${part.length()} из $total байт"),
                    )
                }

                // Размера МАЛО. Оборванная загрузка может дать файл нужной
                // длины с дырой внутри (проверено на устройстве: 189 718 байт
                // `libovpn3.so` пришли нулями, установщик ответил «Приложение не
                // установлено»). Битый файл объявляем неудачей и удаляем: он не
                // должен выглядеть готовым ни на экране, ни в установщике.
                apkIntegrityError(part)?.let { reason ->
                    part.delete()
                    return@withContext Result.failure(IOException(reason))
                }

                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    return@withContext Result.failure(IOException("не удалось сохранить файл"))
                }
                etag.delete()
                onProgress(100)
                Result.success(target)
            }
        } catch (e: IOException) {
            // Огрызок ОСТАЁТСЯ: именно его докачает следующая попытка. Удалять
            // его здесь значило бы начинать стомегабайтную закачку заново при
            // каждом обрыве — то есть вернуть ровно тот дефект, что чиним.
            Result.failure(e)
        }
    }

    private fun open(url: String, from: Long, ifRange: String?): okhttp3.Response {
        val builder = Request.Builder().url(url).get()
        if (from > 0) {
            builder.header("Range", "bytes=$from-")
            // If-Range не обязателен, но защищает от подмены сборки под тем же
            // именем: несовпавшая метка заставит сервер отдать файл с начала,
            // и мы это увидим по коду 200.
            ifRange?.takeIf { it.isNotEmpty() }?.let { builder.header("If-Range", it) }
        }
        return http.newCall(builder.build()).execute()
    }

    /**
     * Убрать файлы чужих версий, сохранив нужные.
     *
     * **Почему версий ДВЕ, и это не перестраховка.** Установленная сборка и та,
     * что скачивается, имеют РАЗНЫЕ номера: скачанный APK лежит как
     * `update-<версия обновления>.apk`, а `BuildConfig.VERSION_CODE` — это версия
     * уже установленного приложения. Когда сюда передавали только её, каждый
     * запуск приложения удалял скачанное обновление как «чужое»: баннер помнил
     * «скачано», а кнопка не находила файл и молчала (дефект 1.0.119).
     *
     * @param keepVersionCodes номера, файлы которых нужно сохранить. Всё
     *   остальное — мусор от прошлых версий.
     */
    fun clearStale(vararg keepVersionCodes: Int) {
        val keep = keepVersionCodes.toSet()
        cacheDir.listFiles()
            ?.filter { versionOf(it.name)?.let { v -> v !in keep } == true }
            ?.forEach { it.delete() }
    }

    companion object {
        fun partFile(cacheDir: File, versionCode: Int) =
            File(cacheDir, "update-$versionCode.apk$PART_SUFFIX")

        fun etagFile(cacheDir: File, versionCode: Int) =
            File(cacheDir, "update-$versionCode.apk$ETAG_SUFFIX")

        /**
         * Скачанный и готовый к установке APK, если он есть.
         *
         * Нужен при возврате в приложение: `WorkManager` помнит завершённую
         * работу, но файл — единственное доказательство, что он ДЕЙСТВИТЕЛЬНО
         * скачан. Проверка файла не даёт показать «Установить» для того, чего
         * на диске нет.
         */
        /**
         * Почему скачанный файл не годится как APK; `null` — годится.
         *
         * **Почему одной сверки размера мало.** Живой случай: Chrome сообщил
         * «скачан 1 файл», размер совпал с серверным побайтово, а внутри
         * `libovpn3.so` 189 718 байт пришли нулями — установщик ответил
         * «Приложение не установлено», и причина была невидима. Оборванная
         * загрузка может сохранить файл нужного размера, но с дырой внутри,
         * поэтому проверять надо ЧИТАЕМОСТЬ архива и наличие манифеста, а не
         * длину.
         *
         * Чистая функция без Android: вызывающий решает, что делать с причиной.
         * Проверка подписи здесь намеренно не делается — она требует
         * `PackageManager`, а её исход всё равно один: файл негоден.
         */
        fun apkIntegrityError(file: File): String? {
            if (!file.exists()) return "файл отсутствует"
            if (file.length() == 0L) return "файл пуст"

            return try {
                java.util.zip.ZipFile(file).use { zip ->
                    val manifest = zip.getEntry("AndroidManifest.xml")
                        ?: return "в архиве нет манифеста — это не APK"
                    // Читаем запись целиком: именно на этом шаге ловится
                    // обнулённый или недописанный кусок внутри файла — размер
                    // при этом может совпадать с ожидаемым.
                    zip.getInputStream(manifest).use { input ->
                        val probe = ByteArray(64)
                        if (input.read(probe) <= 0) return "манифест пуст"
                    }

                    // **CRC считаем сами.** Полагаться на то, что библиотека
                    // заметит порчу, нельзя: на сжатых записях испорченные байты
                    // могут распаковаться «успешно», и тогда проверка молчит. А в
                    // APK нативные библиотеки лежат БЕЗ сжатия — именно их и
                    // обнуляет оборванная загрузка (проверено на устройстве).
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue

                        val crc = java.util.zip.CRC32()
                        var total = 0L
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                crc.update(buffer, 0, read)
                                total += read
                            }
                        }

                        if (total != entry.size) {
                            return "запись «${entry.name}» короче объявленного размера"
                        }
                        if (crc.value != entry.crc) {
                            return "запись «${entry.name}» повреждена (не сходится контрольная сумма)"
                        }
                    }
                    null
                }
            } catch (e: Exception) {
                "файл повреждён: ${e.message ?: "ошибка чтения архива"}"
            }
        }

        fun readyFile(cacheDir: File, versionCode: Int): File? =
            File(cacheDir, "update-$versionCode.apk").takeIf { it.exists() && it.length() > 0 }

        private fun versionOf(name: String): Int? =
            VERSIONED.find(name)?.groupValues?.get(1)?.toIntOrNull()

        private val VERSIONED = Regex("""^update-(\d+)\.apk(\.(part|etag))?$""")
    }
}
