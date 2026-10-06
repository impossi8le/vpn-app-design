package com.impossi8le.vpnapp.tunnel

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.tunnel.TunnelControlling
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
) : TunnelControlling {

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val status: StateFlow<ConnectionStatus> = _status

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
        _status.value = state.toConnectionStatus()
        _appliedBypass.value = appliedBypass
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
