package com.impossi8le.vpnapp.tunnel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.tunnel.TunnelControlling
import com.impossi8le.vpnapp.domain.tunnel.TunnelState
import com.impossi8le.vpnapp.domain.tunnel.TunnelStateStore
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow


/**
 * Управление туннелем со стороны приложения.
 *
 * Сама работа идёт в сервисе — здесь только запуск и остановка, потому что
 * `VpnService` обязан быть сервисом, а не объектом в активности.
 *
 * **Почему `status` здесь не может быть зелёным.** Этот класс отдаёт состояния
 * ТУННЕЛЯ: соединяется, поднят, отключён. Зелёное появляется только после
 * замера, и замер делает `ProtectionGate` в домене. Если бы контроллер умел
 * возвращать `.Protected`, инвариант §6 держался бы на честном слове.
 *
 * Сервис после старта сообщает о себе широковещательными сообщениями; их
 * слушает `TunnelStatusReceiver` и переводит в состояние через
 * `SystemState.toConnectionStatus()`.
 */
class AppTunnelController(
    private val context: Context,
    private val serviceClass: Class<*>,
    private val actionConnect: String,
    private val actionDisconnect: String,
    private val extraProfile: String,
    /**
     * Абсолютный путь к `.ovpn` в приватном каталоге приложения.
     *
     * Именно путь, а не текст: содержимое профиля — с приватным ключом, и
     * класть его в extras намерения значит отдать системе, где он осядет в
     * логах и истории. Сервис читает файл сам.
     */
    private val profilePath: String,
    /** Ключ extra, под которым сервис ждёт путь к файлу обходов. */
    private val extraBypass: String,
    /**
     * Абсолютный путь к файлу обходов (по строке `network/prefix`), или `null`,
     * если обходов нет.
     *
     * Именно путь, а не содержимое: сервис читает файл сам. `null` — сервису
     * не передаётся ничего, и он поднимает туннель без исключений: отсутствие
     * обходов не повод не подключаться.
     */
    private val bypassPath: String? = null,
    /**
     * Чем проверяем, что туннель ещё существует. По умолчанию — системное чтение
     * через [AndroidTunnelPresence]; в тестах подставляется фейк.
     */
    presenceProbe: TunnelPresenceProbe? = null,
    /** Как часто перепроверять существование туннеля, пока он «поднят». */
    presenceInterval: Duration = TunnelPresenceWatcher.DEFAULT_INTERVAL,
) : TunnelControlling {

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val status: StateFlow<ConnectionStatus> = _status

    /**
     * Сторож существования туннеля.
     *
     * Пока состояние описывает поднятый туннель, раз в [presenceInterval]
     * спрашивает систему, есть ли VPN-интерфейс, и при исчезновении гасит
     * состояние в [ConnectionStatus.Disconnected]. Собственный скоуп, а не чужой:
     * контроллер — долгоживущий объект без своего жизненного цикла, а задания
     * короткие и завершаются сами.
     */
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Чтение факта «туннель над трафиком есть».
     *
     * Один экземпляр на двоих: сторож проверяет им пропажу туннеля, а
     * `onServiceState` — не объявлять «подключено» раньше факта (issue #32).
     * Два источника одного факта разошлись бы при первой же правке.
     */
    private val tunnelPresence: TunnelPresenceProbe = presenceProbe
        ?: AndroidTunnelPresence(
            context.getSystemService(android.net.ConnectivityManager::class.java)
                ?: error("ConnectivityManager недоступен"),
        )

    private val presenceWatcher = TunnelPresenceWatcher(
        probe = tunnelPresence,
        interval = presenceInterval,
    )

    /**
     * Сколько обходов сервис РЕАЛЬНО применил (исключений `excludeRoute`,
     * вернувших `true`).
     *
     * Экран показывает именно это число, а не количество строк в файле обходов:
     * строка может не разобраться, а на API < 33 исключения невозможны вовсе.
     * Показать число строк значило бы заявить об обходе, которого на устройстве
     * нет, — недоказанное утверждение, запрещённое §6. Дефолт `0` — безопасная
     * сторона: пока сервис не подтвердил обход, «применено ноль».
     */
    private val _appliedBypass = MutableStateFlow(0)
    val appliedBypass: StateFlow<Int> = _appliedBypass.asStateFlow()

    /**
     * Смена сети.
     *
     * Отдельный канал, а не часть `status`: `StateFlow` схлопывает одинаковые
     * значения, поэтому «тот же статус, другая сеть» через него не выразить.
     * А для §6 это принципиально — замер, сделанный в прежней сети, после смены
     * недействителен, и зелёное обязано исчезнуть.
     */
    private val _networkChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val networkChanges: Flow<Unit> = _networkChanges.asSharedFlow()

    override suspend fun connect() {
        _status.value = ConnectionStatus.Connecting

        // СОГЛАСИЕ НА VPN ЗАПРАШИВАЕТСЯ ЗДЕСЬ, и это не формальность.
        //
        // Система не даёт приложению поднять VPN без явного согласия
        // пользователя. `VpnService.prepare()` возвращает `null`, если согласие
        // уже дано, и намерение, которое надо показать, если нет. Без этого
        // вызова `Builder.establish()` вернёт `null`, и туннель не поднимется —
        // ровно так и было: ядро стартовало, печатало свою версию и замолкало,
        // потому что интерфейс создать было нечем.
        val consent = VpnService.prepare(context)
        if (consent != null) {
            // Показать диалог может только активность. Контроллер живёт в
            // application-контексте, поэтому вместо запуска оттуда отдаём
            // намерение наружу: экран покажет его и, получив согласие, повторит
            // connect(). Так согласие не теряется, а попытка не «зависает».
            _status.value = ConnectionStatus.Disconnected
            onConsentRequired?.invoke(consent)
            return
        }

        context.startForegroundService(connectIntent())
    }

    /**
     * Экран показал диалог согласия и получил его.
     *
     * Вызывается повторно после согласия: до этого момента туннель поднимать
     * бессмысленно — система не даст.
     */
    fun onConsentGranted() {
        context.startForegroundService(connectIntent())
    }

    /**
     * Куда отдать намерение согласия. Ставит экран.
     *
     * Без этого колбэка поведение прежнее: попытка подключения без согласия
     * честно завершится отказом, а не тихим зависанием.
     */
    var onConsentRequired: ((android.content.Intent) -> Unit)? = null

    override suspend fun disconnect() {
        context.startService(Intent(context, serviceClass).setAction(actionDisconnect))
        _status.value = ConnectionStatus.Disconnected
        // Сторож гасим сразу...
        presenceWatcher.stop()
        // ...и сообщаем общий источник правды. Сервис опубликует то же самое
        // своим notifyState(IDLE), но ТОЛЬКО если ему было что останавливать:
        // одиночный DISCONNECT по незапущенному сервису не рассылает состояние,
        // и снимок остался бы прежним — плитка показывала бы «подключён» при уже
        // опущенном туннеле. Пишем здесь, чтобы «отключено» было видно обоим.
        TunnelStateStore.publish(TunnelState.Idle)
    }

    override suspend fun reverifyProtection() {
        // Сама перепроверка — в домене; здесь только сигнал «сеть изменилась»,
        // чтобы экран сбросил прежнее подтверждение.
        onNetworkChanged()
    }

    /** Вызывается слушателем системных событий сети. */
    fun onNetworkChanged() {
        _networkChanges.tryEmit(Unit)
    }

    /**
     * Обновление состояния из сервиса.
     *
     * [appliedBypass] — число исключений, которые сервис реально применил. Едет
     * вместе с состоянием: экран обязан показать применённое, а не число строк
     * в файле (см. [_appliedBypass]).
     */
    internal fun onServiceState(state: SystemState, appliedBypass: Int) {
        // «Подключено» подтверждается фактом, а не только событием сервиса:
        // решение и причина — в `confirmedByTunnelPresence` (issue #32).
        val declared = state.toConnectionStatus()
        _status.value = declared.confirmedByTunnelPresence(tunnelPresence.read())
        _appliedBypass.value = appliedBypass
        // Состояние сменилось — пересобрать сторож существования туннеля. Именно
        // здесь, а не в `TunnelStatusReceiver`: сервис может прислать `ESTABLISHED`
        // и умолкнуть навсегда, и тогда единственный признак жизни — наш опрос.
        restartPresenceWatch()
    }

    /**
     * Перезапустить сторож существования туннеля под текущее состояние.
     *
     * Решение и цикл живут в [TunnelPresenceWatcher] — там же, где их проверяет
     * тест на виртуальном времени. Здесь только вкручиваем источник состояния и
     * приёмник вердикта.
     */
    private fun restartPresenceWatch() {
        presenceWatcher.restart(
            scope = watchScope,
            current = { _status.value },
            onTunnelLost = { lost ->
                _status.value = lost
                // Место, где расхождение возникало наяву. Туннель исчез, НЕ
                // прислав `Disconnected`: интерфейс снёс кто-то извне (см.
                // `TunnelPresenceWatcher`). Экран узнаёт об этом отсюда, и снимок
                // обязан смениться ровно здесь же — иначе плитка осталась бы
                // «подключён», пока экран уже говорит «не подключено».
                TunnelStateStore.publish(TunnelState.Idle)
            },
        )
    }

    /**
     * Отпустить ресурсы контроллера: экран ушёл, опрашивать систему дальше не за
     * чем.
     *
     * Без этого сторож жил бы до смерти процесса. При пересоздании активности
     * (поворот экрана) создаётся новый контроллер и новый сторож, а старый так и
     * продолжал бы дёргать `ConnectivityManager` каждые несколько секунд —
     * по одному висящему циклу на каждый поворот.
     */
    fun release() {
        presenceWatcher.stop()
        watchScope.cancel()
    }

    private fun connectIntent(): Intent =
        Intent(context, serviceClass).apply {
            action = actionConnect
            // Путь к профилю, а не сам профиль: содержимое содержит приватный
            // ключ и не должно попадать в extras намерения.
            putExtra(extraProfile, profilePath)
            // Путь к файлу обходов кладём ТОЛЬКО когда он есть: пустой extra
            // сервису ничего не добавит, а `null`-путь он и так трактует как
            // «обходов нет».
            bypassPath?.let { putExtra(extraBypass, it) }
        }
}
