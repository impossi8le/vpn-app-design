package com.impossi8le.vpnapp.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.impossi8le.vpnapp.core.ui.UpdateDownloadState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Постановка и наблюдение фоновой закачки APK.
 *
 * Единственная уникальная работа на приложение: повторная постановка с
 * [ExistingWorkPolicy.KEEP] не плодит вторую закачку — экран видел три
 * параллельных запроса одного APK, когда флаг защиты жил в композиции и
 * сбрасывался при пересоздании активности.
 */
object UpdateDownloadScheduler {

    /** Начать или продолжить закачку. Огрызок прошлой попытки подхватится сам. */
    fun enqueue(context: Context, url: String, versionCode: Int) {
        val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ApkDownloadWorker.KEY_URL, url)
                    .putInt(ApkDownloadWorker.KEY_VERSION_CODE, versionCode)
                    .build(),
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS,
            )
            .build()

        // REPLACE, а не KEEP: пользователь мог отложить и передумать, а ссылка
        // между проверкой и нажатием успевает обновиться. KEEP оставил бы
        // старую закачку со старым URL.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                ApkDownloadWorker.UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
    }

    /**
     * Пауза: отменяет работу, СОХРАНЯЯ огрызок на диске. Продолжение пойдёт с
     * того же места — за это отвечает `Range` в [ApkDownloader].
     */
    fun pause(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ApkDownloadWorker.UNIQUE_NAME)
    }

    /**
     * Отложить: отменяет работу и убирает огрызок. Отличие от паузы
     * принципиально — «Позже» значит «не хочу занимать место», и оставлять
     * 106 МБ в кеше было бы против ожидания пользователя.
     */
    fun cancel(context: Context, versionCode: Int) {
        WorkManager.getInstance(context).cancelUniqueWork(ApkDownloadWorker.UNIQUE_NAME)
        ApkDownloader.partFile(context.cacheDir, versionCode).delete()
        ApkDownloader.etagFile(context.cacheDir, versionCode).delete()
    }

    /**
     * Состояние закачки для экрана.
     *
     * `WorkInfo` различает RUNNING / ENQUEUED / SUCCEEDED / FAILED, но не
     * `CANCELLED`-из-за-паузы от `CANCELLED`-из-за-отмены. Это различие держит
     * экран: он сам решает, показать «Продолжить» или вернуть баннер в исходный
     * вид, — а сюда пауза и отмена приходят разными вызовами.
     */
    fun observe(context: Context): Flow<UpdateDownloadState> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(ApkDownloadWorker.UNIQUE_NAME)
            .map { infos ->
                val info = infos.firstOrNull() ?: return@map UpdateDownloadState.Idle
                toState(info.state, info.progress.getInt(ApkDownloadWorker.KEY_PERCENT, 0))
            }

    /**
     * Перевод состояния работы в состояние экрана — чистой функцией.
     *
     * Отдельно от [observe]: здесь единственное место, где решается, что увидит
     * человек, и оно должно проверяться тестом, а не только вживую на телефоне.
     */
    fun toState(state: WorkInfo.State, percent: Int): UpdateDownloadState =
        when (state) {
            WorkInfo.State.RUNNING -> UpdateDownloadState.Downloading(percent)

            // В очереди после паузы — показываем прежний процент, а не ноль:
            // огрызок на диске никуда не делся, и продолжение пойдёт с него.
            WorkInfo.State.ENQUEUED -> UpdateDownloadState.Paused(percent)

            WorkInfo.State.SUCCEEDED -> UpdateDownloadState.Ready

            WorkInfo.State.FAILED -> UpdateDownloadState.Failed("скачивание не удалось")

            WorkInfo.State.CANCELLED, WorkInfo.State.BLOCKED -> UpdateDownloadState.Idle
        }
}
