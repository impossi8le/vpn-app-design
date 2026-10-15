package com.impossi8le.vpnapp.telemetry

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.tunnel.TunnelControlling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Что известно о работающем подключении в момент события.
 *
 * Отдельная структура, а не три параметра: те же три значения нужны и в
 * `state_change`, и в `connect_attempt`, и передавать их поодиночке значило бы
 * плодить одинаковые списки аргументов.
 */
data class TelemetryContext(
    val configId: String?,
    val configName: String?,
    val server: String?,
)

/**
 * Подписка на состояние туннеля, пишущая события в [TelemetryRecorder].
 *
 * Без неё `TelemetryRecorder` некому вызвать, и очередь авто-метрик остаётся
 * пустой — ровно та причина, по которой авто-контур молчал.
 *
 * **Почему `drop(1)`.** `TunnelControlling.status` — `StateFlow`, и он отдаёт
 * текущее значение КАЖДОМУ новому подписчику. Первое значение — не изменение, а
 * повтор: без его отбрасывания пересоздание активности записывало бы событие
 * «подключено» на каждый поворот экрана. Событие должно рождаться из СМЕНЫ
 * состояния, а не из факта подписки.
 *
 * **Почему снаружи и скоп, и часы.** Скоп принадлежит экрану: подписка должна
 * умереть вместе с ним, иначе после ухода экрана остался бы висеть коллектор.
 * Часы снаружи — чтобы длительность подключения проверялась тестом, а не
 * зависела от реального времени.
 */
class TunnelTelemetryWatcher(
    private val tunnel: TunnelControlling,
    private val recorder: TelemetryRecorder,
    /** Контекст подключения на момент события: id, имя, адрес сервера. */
    private val context: () -> TelemetryContext = { TelemetryContext(null, null, null) },
    /**
     * Монотонные миллисекунды. Монотонные, а не системные: перевод часов не
     * должен сделать длительность отрицательной. `System.nanoTime` выбран ещё и
     * потому, что не тянет Android: тест подписки идёт на JVM, где вызов
     * платформенного `SystemClock` бросает «not mocked» и убивает коллектор —
     * очередь оставалась бы пустой, а причина выглядела бы как «событие не пришло».
     */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val dispatcher: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) {

    private var job: Job? = null

    /** Момент начала последней попытки подключения; `null` — попытки нет. */
    private var connectStartedAtMs: Long? = null

    /** Начать наблюдение. Повторный вызов на живом наблюдении ничего не делает. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(dispatcher) {
            tunnel.status
                .drop(1)
                .collect { status -> record(status) }
        }
    }

    /** Остановить наблюдение: экран ушёл, писать больше некому. */
    fun stop() {
        job?.cancel()
        job = null
        connectStartedAtMs = null
    }

    private suspend fun record(status: ConnectionStatus) {
        val ctx = context()
        if (status is ConnectionStatus.Connecting && connectStartedAtMs == null) {
            connectStartedAtMs = nowMs()
        }

        recorder.stateChanged(
            state = status.telemetryState(),
            configId = ctx.configId,
            configName = ctx.configName,
            server = ctx.server,
        )

        when (status) {
            is ConnectionStatus.Failed -> {
                // Код машинный, а не текст причины: по нему сервер строит
                // статистику. Человеческая формулировка причины в код не годится —
                // её нельзя сравнивать между версиями и сборками.
                recorder.error(code = ERROR_TUNNEL_FAILED, state = status.telemetryState())
                finishAttempt(status)
            }

            // Дошли до туннеля — попытка закончилась, считаем её длительность.
            // `ProtectionFailed` сюда же: подключение состоялось, а замер —
            // отдельная история (§6), и она не отменяет факта подключения.
            ConnectionStatus.VerifyingProtection,
            is ConnectionStatus.Protected,
            is ConnectionStatus.ProtectionFailed,
            -> finishAttempt(status)

            else -> Unit
        }
    }

    private suspend fun finishAttempt(status: ConnectionStatus) {
        val started = connectStartedAtMs ?: return
        connectStartedAtMs = null
        val duration = (nowMs() - started).coerceAtLeast(0)
        recorder.connectAttempt(state = status.telemetryState(), durationMs = duration)
    }

    companion object {
        /**
         * Код ошибки, когда туннель не поднялся.
         *
         * Константа, а не текст причины: `ConnectionStatus.Failed` несёт
         * человеческую формулировку, и она меняется вместе с UI. Сервер должен
         * получать устойчивый идентификатор, а не строку интерфейса.
         */
        const val ERROR_TUNNEL_FAILED = "tunnel_failed"
    }
}

/**
 * Машинное состояние туннеля для события телеметрии.
 *
 * Отдельно от `ConnectionStatus.label()`: та даёт текст для человека («ошибка:
 * …»), а серверу нужен устойчивый код. `Protected` и `VerifyingProtection` дают
 * РАЗНЫЕ коды намеренно: первое доказано замером, второе — нет, и смешать их в
 * одно «connected» значило бы снова стереть границу §6, только уже в данных.
 */
internal fun ConnectionStatus.telemetryState(): String = when (this) {
    ConnectionStatus.Disconnected -> "disconnected"
    ConnectionStatus.Connecting -> "connecting"
    ConnectionStatus.VerifyingProtection -> "connected"
    is ConnectionStatus.Protected -> "protected"
    is ConnectionStatus.ProtectionFailed -> "protection_failed"
    is ConnectionStatus.Failed -> "failed"
}
