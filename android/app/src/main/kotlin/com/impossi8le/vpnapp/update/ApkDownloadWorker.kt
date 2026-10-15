package com.impossi8le.vpnapp.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.impossi8le.vpnapp.network.ApiClient

/**
 * Фоновая закачка APK обновления.
 *
 * **Почему воркер, а не корутина экрана.** Раньше закачка жила в `scope.launch`
 * внутри `MainActivity`: уход из приложения, поворот телефона или пересоздание
 * активности убивали её, и прогресс терялся. 106 МБ на медленной сети в это
 * окно не укладываются, поэтому пользователь не мог обновиться, не держа
 * приложение открытым. Воркер переживает и уход в фон, и перезапуск процесса.
 *
 * **Отмена паузы — это отмена воркера.** `CancellationException` гасит цикл
 * скачивания, но огрызок остаётся на диске: продолжение (`Range`) начнётся с
 * того же места. Именно ради этого [ApkDownloader] не удаляет огрызок при обрыве.
 */
class ApkDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val version = inputData.getInt(KEY_VERSION_CODE, 0)
        if (url.isEmpty() || version <= 0) return Result.failure()

        val downloader = ApkDownloader(ApiClient.downloadClient(), applicationContext.cacheDir)

        val result = downloader.download(url, version) { percent ->
            // Прогресс идёт наверх через setProgress; на экране он читается
            // подпиской на WorkInfo. `Data` кладёт int как есть.
            setProgressAsync(Data.Builder().putInt(KEY_PERCENT, percent).build())
        }

        return if (result.isSuccess) {
            Result.success()
        } else {
            // Retry, а не failure: обрыв сети — временная причина, и WorkManager
            // повторит попытку с backoff, а докачка начнётся с того же места.
            Result.retry()
        }
    }

    companion object {
        const val KEY_URL = "url"
        const val KEY_VERSION_CODE = "version_code"
        const val KEY_PERCENT = "percent"

        /** Имя уникальной работы: повторная постановка обновляет, а не плодит. */
        const val UNIQUE_NAME = "com.impossi8le.vpnapp.update.apk"

        /** Ограничение по батарее снято намеренно: обновление — действие пользователя. */
        const val MAX_BACKOFF_SECONDS = 60L
    }
}
