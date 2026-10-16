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

    /**
     * Контекст подключения, снятый, пока туннель был жив.
     *
     * Нужен ровно для разрыва. `context()` читает ТЕКУЩИЕ мета/выбор, а при смене
     * конфига они уже указывают на новый сервер к моменту, когда старый
     * отключается: профиль нового пишется ДО `disconnect()` (см. `performSwitch`),
     * и событие `disconnected` помечалось бы новым конфигом (issue #25). Поэтому
     * помним тот контекст, что был актуален, когда подключение поднималось, и
     * именно им помечаем разрыв.
     */
    private var activeContext: TelemetryContext? = null

    /**
     * Просил ли пользователь остановить подключение прямо сейчас.
     *
     * `@Volatile`: ставится с главного потока (экран), читается и гасится в
     * коллекторе на `Dispatchers.IO`. Без этого отмена на другом потоке могла не
     * дойти до обработчика разрыва, и отмена была бы записана как сбой (issue #26).
     */
    @Volatile
    private var userStopRequested = false

    /**
     * Отметить, что остановку запросил пользователь, а не туннель оборвался сам.
     *
     * Зовётся ПЕРЕД `disconnect()`: разрыв, пришедший следом, будет записан как
     * отмена, а не как сбой. Флаг живёт до первого изменения состояния — его
     * гасит `record`, — поэтому «залипнуть» и перепутать следующий разрыв он не
     * может.
     */
    fun noteUserDisconnect() {
        userStopRequested = true
    }

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
        // Новый экран начнёт с чистого листа: чужой разрыв не должен считаться
        // отменой по флагу, оставшемуся от прежней жизни наблюдателя.
        userStopRequested = false
        activeContext = null
    }

    private suspend fun record(status: ConnectionStatus) {
        if (status is ConnectionStatus.Connecting && connectStartedAtMs == null) {
            connectStartedAtMs = nowMs()
        }

        // Останов просил пользователь — важно только на разрыве и строго на нём.
        // Читаем и гасим на каждом изменении: иначе флаг пережил бы свою смену и
        // пометил чужой разрыв отменой.
        val userStop = userStopRequested
        userStopRequested = false

        // Метою контекста НЕ «текущее» значение: при смене конфига мета профиля
        // уже указывает на новый сервер, и `disconnected` старого туннеля ушёл бы
        // с чужим id (issue #25). См. `resolveContext`.
        val ctx = resolveContext(status)

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
                recorder.error(
                    code = ERROR_TUNNEL_FAILED,
                    state = status.telemetryState(),
                    configId = ctx.configId,
                    configName = ctx.configName,
                    server = ctx.server,
                )
                finishAttempt(status, ctx)
            }

            // Дошли до туннеля — попытка закончилась, считаем её длительность.
            // `ProtectionFailed` сюда же: подключение состоялось, а замер —
            // отдельная история (§6), и она не отменяет факта подключения.
            ConnectionStatus.VerifyingProtection,
            is ConnectionStatus.Protected,
            is ConnectionStatus.ProtectionFailed,
            -> finishAttempt(status, ctx)

            // Соединение ушло в «отключено», не дойдя до туннеля. Исход попытки
            // зависит от того, кто её прекратил: пользователь или сеть (issue #26).
            ConnectionStatus.Disconnected ->
                if (userStop) cancelAttempt(ctx) else breakAttempt(ctx)

            else -> Unit
        }
    }

    /**
     * Контекст, которым помечается событие состояния.
     *
     * Для состояний, описывающих ПОДНИМАЕМЫЙ или ПОДНЯТЫЙ туннель, — это свежий
     * `context()`: он верен, и его же запоминаем как «работающий». Для
     * [ConnectionStatus.Disconnected] и [ConnectionStatus.Failed] — это ПРЕЖНИЙ
     * контекст: событие описывает то подключение, которое завершается, а не
     * будущее. Без этого `disconnected` при смене конфига помечался бы новым
     * сервером (issue #25).
     */
    private fun resolveContext(status: ConnectionStatus): TelemetryContext = when (status) {
        ConnectionStatus.Connecting,
        ConnectionStatus.VerifyingProtection,
        is ConnectionStatus.Protected,
        is ConnectionStatus.ProtectionFailed,
        -> context().also { activeContext = it }

        // Туннель перезапускается с тем же профилем — конфиг не менялся.
        ConnectionStatus.Recovering -> activeContext ?: context()

        // Завершение: метка — то, что было активно, а не то, что выбрано сейчас.
        ConnectionStatus.Disconnected,
        is ConnectionStatus.Failed,
        -> activeContext ?: context()
    }

    private suspend fun finishAttempt(status: ConnectionStatus, ctx: TelemetryContext) {
        val started = connectStartedAtMs ?: return
        connectStartedAtMs = null
        val duration = (nowMs() - started).coerceAtLeast(0)
        recorder.connectAttempt(
            state = status.telemetryState(),
            durationMs = duration,
            configId = ctx.configId,
            configName = ctx.configName,
            server = ctx.server,
        )
    }

    /**
     * Сбой: попытка оборвалась сама, туннель не поднялся.
     *
     * Пишем ДВА события, и это не дубль. `connect_attempt` с исходом
     * [STATE_ABANDONED] закрывает таймер попытки (иначе время простоя
     * приклеилось бы к следующему подключению — в метрику ушла бы сумма вместо
     * секунды). `error` нужен отдельно: именно его не хватало в проде — 34
     * обрыва и одно событие `error` (issue #26). Без него по данным не отличить
     * сбой от тихой отмены: оставался бы голый `disconnected`.
     */
    private suspend fun breakAttempt(ctx: TelemetryContext) {
        val started = connectStartedAtMs ?: return
        connectStartedAtMs = null
        val duration = (nowMs() - started).coerceAtLeast(0)
        recorder.error(
            code = ERROR_TUNNEL_DROPPED,
            state = ConnectionStatus.Disconnected.telemetryState(),
            configId = ctx.configId,
            configName = ctx.configName,
            server = ctx.server,
        )
        recorder.connectAttempt(
            state = STATE_ABANDONED,
            durationMs = duration,
            configId = ctx.configId,
            configName = ctx.configName,
            server = ctx.server,
        )
    }

    /**
     * Отмена: попытку прекратил пользователь, туннель не поднялся.
     *
     * Записываем `connect_attempt` с исходом [STATE_CANCELLED], но НЕ `error`:
     * «пользователь передумал» — не сбой, и валить её в статистику ошибок значит
     * завышать счёт проблем на сервере. Отдельный исход при этом обязателен —
     * иначе отмена неотличима от обрыва и «34 обрыва» нечем прочитать.
     */
    private suspend fun cancelAttempt(ctx: TelemetryContext) {
        val started = connectStartedAtMs ?: return
        connectStartedAtMs = null
        val duration = (nowMs() - started).coerceAtLeast(0)
        recorder.connectAttempt(
            state = STATE_CANCELLED,
            durationMs = duration,
            configId = ctx.configId,
            configName = ctx.configName,
            server = ctx.server,
        )
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

        /**
         * Код ошибки, когда попытка оборвалась сама (таймаут рукопожатия, сервер
         * не ответил, ядро вернуло ошибку без `Failed`).
         *
         * Отличается от [ERROR_TUNNEL_FAILED] тем, что домен НЕ перешёл в
         * `Failed`: соединение просто ушло в «отключено». Без этого кода такие
         * обрывы в канал `error` не попадали вовсе (issue #26).
         */
        const val ERROR_TUNNEL_DROPPED = "tunnel_dropped"

        /**
         * Исход оборванной попытки: соединение ушло в «отключено», не подняв туннель.
         *
         * Отдельный код, а не `disconnected`: обычная остановка и оборванная
         * попытка — разные события, и смешать их значило бы потерять счёт
         * неудачных подключений.
         */
        const val STATE_ABANDONED = "abandoned"

        /**
         * Исход отменённой попытки: пользователь нажал «Отменить».
         *
         * Отдельный от [STATE_ABANDONED] код, потому что отмена и сбой требуют
         * противоположных выводов: первое — норма, второе — проблема с сервером.
         */
        const val STATE_CANCELLED = "cancelled"
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
    // Свой код, а не "connecting": перебой — отдельное событие, и в данных он
    // не должен слиться с первым подключением, иначе причину частых обрывов
    // не увидеть.
    ConnectionStatus.Recovering -> "recovering"
    ConnectionStatus.VerifyingProtection -> "connected"
    is ConnectionStatus.Protected -> "protected"
    is ConnectionStatus.ProtectionFailed -> "protection_failed"
    is ConnectionStatus.Failed -> "failed"
}
