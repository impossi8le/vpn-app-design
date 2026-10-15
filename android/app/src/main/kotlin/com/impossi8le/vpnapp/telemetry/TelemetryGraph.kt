package com.impossi8le.vpnapp.telemetry

import android.content.Context
import com.impossi8le.vpnapp.API_BASE_URL
import com.impossi8le.vpnapp.BuildConfig
import com.impossi8le.vpnapp.domain.diagnostics.FileReportQueue
import com.impossi8le.vpnapp.domain.diagnostics.ReportQueue
import com.impossi8le.vpnapp.network.ApiClient
import com.impossi8le.vpnapp.network.TelemetryApi
import com.impossi8le.vpnapp.security.AndroidSecureBackend
import com.impossi8le.vpnapp.security.SessionStoreImpl

/**
 * Сборка зависимостей воркера.
 *
 * Воркер создаётся WorkManager'ом, а не композицией экрана, поэтому `AppGraph` в
 * нём нет и передать его нельзя. Граф здесь тонкий и повторяет `AppGraph` ровно
 * в той части, что нужна фоновой отправке: базовый адрес, токен сессии, файловая
 * очередь и версия сборки. Отдельный DI не заводим — четыре зависимости.
 */
class TelemetryGraph(
    val api: TelemetryApi,
    val queue: ReportQueue,
    private val appVersionCode: Int,
) {
    fun appVersion(): Int = appVersionCode

    companion object {
        /** Собрать граф по контексту: иначе воркеру неоткуда взять зависимости. */
        fun from(context: Context): TelemetryGraph {
            val app = context.applicationContext
            val client = ApiClient(baseUrl = API_BASE_URL)
            // Токен нужен для авторизованного батча: без него сервер ответит 401,
            // и очередь будет вычищена впустую. Читаем сохранённую сессию.
            client.sessionToken = SessionStoreImpl(AndroidSecureBackend(app)).load()?.token
            return TelemetryGraph(
                api = TelemetryApi(client),
                queue = FileReportQueue(java.io.File(app.filesDir, QUEUE_FILE_NAME)),
                appVersionCode = BuildConfig.VERSION_CODE,
            )
        }

        /** Тот же файл, что читал бы экран: одна очередь на приложение. */
        const val QUEUE_FILE_NAME = "telemetry-queue.jsonl"
    }
}
