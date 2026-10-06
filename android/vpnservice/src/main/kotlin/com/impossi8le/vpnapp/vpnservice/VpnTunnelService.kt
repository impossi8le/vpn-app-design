package com.impossi8le.vpnapp.vpnservice

import android.app.NotificationManager
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.impossi8le.vpnapp.vpnengine.EngineState
import com.impossi8le.vpnapp.vpnengine.OpenVpn3Session
import com.impossi8le.vpnapp.domain.tunnel.parseBypassCidr
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Системное состояние сервиса, передаваемое наружу по широковещанию.
 *
 * Копия `core:tunnel.SystemState`, а не ссылка на него: `:vpnservice` не
 * зависит от `core:tunnel` и не может — это `core:tunnel` оборачивает
 * `:vpnservice` (§4.5). Дублируется ТОЛЬКО enum и строки действий, и они
 * обязаны совпадать с `TunnelStatusReceiver` дословно, иначе экран не увидит
 * сервис. Домен этот тип не видит: наружу уходит имя, а маппинг в
 * `ConnectionStatus` (никогда не зелёный) делает `core:tunnel`.
 */
enum class SystemState { IDLE, CONNECTING, ESTABLISHED, LOST, FAILED }

/**
 * Текст уведомления по системному состоянию.
 *
 * `null` — «уведомление снять»: в покое держать его не за чем, а висящее
 * «Подключено» после отключения было бы ложью в шторке.
 *
 * Функция чистая и живёт вне сервиса: уведомление нельзя показать в тесте без
 * устройства, а правило «какое состояние — какой текст» проверить можно и нужно.
 */
internal fun notificationTextFor(state: SystemState): String? = when (state) {
    SystemState.IDLE -> null
    SystemState.CONNECTING -> "Подключение…"
    SystemState.ESTABLISHED -> "Подключено"
    SystemState.LOST -> "Соединение потеряно"
    SystemState.FAILED -> "Не удалось подключиться"
}

/**
 * Сервис туннеля.
 *
 * Здесь живёт `VpnService` и мост к ядру OpenVPN 3. Разделение с `core:tunnel`
 * как на iOS: приложение только просит поднять туннель, поднимает его сервис.
 *
 * **Про `foregroundServiceType="specialUse"`.** Тип `dataSync` для VPN
 * запрещён: Android 14 обрывает такие сервисы через шесть часов. VPN должен жить
 * дольше, поэтому в манифесте стоит `specialUse` с пояснением назначения.
 *
 * **Про `setBlocking`.** Он блокирует трафик, только пока жив интерфейс. Смерть
 * сервиса снимает блокировку, и трафик уходит открытым — это НЕ killswitch,
 * вопреки распространённому мнению. Единственная защита, переживающая такая
 * смерть, — Always-on VPN, и включает её пользователь в системных настройках.
 */
class VpnTunnelService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // @Volatile — потому что чтение идёт из onStartCommand (главный поток), а
    // запись — из корутины на Dispatchers.IO. Без него главный поток мог бы не
    // увидеть поднятую сессию, и гонка «отключение пропустило старт» стала бы
    // ещё реальнее.
    @Volatile
    private var session: OpenVpn3Session? = null

    /**
     * Подключение в работе. Отменяется при отключении.
     *
     * Гонка настоящая: отключение может прийти, пока профиль ещё читается с
     * диска или пока ядро дозванивается. Без общей ручки отмены туннель поднялся
     * бы уже после того, как пользователь велел отключиться.
     */
    @Volatile
    private var connectJob: Job? = null

    /**
     * Отмену подключения запоминаем отдельно.
     *
     * Отмена корутины доставляет `CancellationException` только на следующей
     * точке приостановки. Между чтением файла и `start()` их нет, так что
     * `isActive` ещё остаётся true, и одинокая проверка пропустила бы отмену —
     * ядро подняло бы туннель уже после приказа «отключить». Флаг взводится
     * синхронно в момент отмены и не зависит от планировщика.
     */
    @Volatile
    private var connectCancelled = false

    /** Туннель остановлен: поздние события ядра не должны оживлять уведомление. */
    private var stopped = false

    private lateinit var connectivity: ConnectivityManager

    /**
     * Следит за сетью и сообщает слушателю о её смене.
     *
     * Инвариант §6 требует сбрасывать зелёное при смене сети, и этого нельзя
     * выразить через состояние туннеля: `StateFlow` схлопывает одинаковые
     * значения, поэтому «тот же статус, другая сеть» через него не проходит.
     * Отсюда отдельное событие. Снимается в [onDestroy] обязательно — иначе
     * система держит ссылку на сервис после его смерти, и это утечка.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) = notifyNetworkChanged()

        override fun onAvailable(network: Network) = notifyNetworkChanged()
    }

    override fun onCreate() {
        super.onCreate()
        // `getSystemService(Class)` в Kotlin nullable: отсутствие сервиса —
        // аварийная ситуация, и лучше упасть на старте, чем уронить onDestroy
        // поздним обращением к неназначенному полю.
        connectivity = getSystemService(ConnectivityManager::class.java)
            ?: error("ConnectivityManager недоступен")
        // registerDefaultNetworkCallback доступен с API 24, наш minSdk 26 — можно.
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                // Читаем ПУТЬ, а не текст: содержимое профиля с приватным ключом
                // в extras намерения было бы видно системе. Чтение — на IO.
                val path = intent.getStringExtra(EXTRA_PROFILE)
                if (path.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startTunnel(path, intent.getStringExtra(EXTRA_BYPASS_FILE))
            }

            ACTION_DISCONNECT -> stopTunnel()
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(profilePath: String, bypassFilePath: String?) {
        // Идемпотентно: повторный CONNECT не должен поднимать второй туннель.
        // Проверяем и сессию, и незавершённое подключение: во время чтения файла
        // сессия ещё null, и без второй проверки второй CONNECT стартовал бы
        // параллельно.
        if (session != null || connectJob?.isActive == true) return

        stopped = false

        // startForeground ОБЯЗАТЕЛЕН и вызывается ПЕРВЫМ делом.
        //
        // Контроллер стартует сервис через startForegroundService(), а такой
        // запуск требует, чтобы сервис в течение нескольких секунд вызвал
        // startForeground() с уведомлением. Иначе система убивает его, и на
        // Android 8+ это выглядит как падение приложения сразу после нажатия
        // «Подключить» — без внятной причины в логах.
        //
        // Отдельно: тип сервиса в манифесте — `specialUse`, а не `dataSync`.
        // `dataSync` для VPN запрещён: Android 14 обрывает такие сервисы через
        // шесть часов, а туннель должен жить дольше.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(notificationTextFor(SystemState.CONNECTING) ?: "Подключение…"),
        )

        notifyState(SystemState.CONNECTING)
        connectCancelled = false
        connectJob = scope.launch {
            // Файл читается здесь, на IO, и уже ПОСЛЕ старта: `start()` ядра
            // блокирующий, поэтому и чтение, и запуск живут в одной корутине.
            // Отказ честный — сервис сообщает FAILED, а не делает вид, что
            // туннель поднимается.
            val profileText = runCatching { File(profilePath).readText() }.getOrNull()
            if (profileText.isNullOrBlank()) {
                notifyState(SystemState.FAILED)
                // Обнуляем ручку подключения, чтобы последовавший onDestroy не
                // перекрыл FAILED'ом-наоборот: stopTunnel иначе счёл бы работу
                // активной и прислал IDLE поверх честного отказа.
                connectJob = null
                stopSelf()
                return@launch
            }

            val builder = Builder()
                .setSession(SESSION_NAME)
                // Блокировка на время жизни интерфейса. Не killswitch: см. doc-класс.
                .setBlocking(true)
                .setMtu(DEFAULT_MTU)

            val tun = VpnServiceTunBuilder(this@VpnTunnelService, builder)

            // Обходы применяются ДО establish(): маршрут добавляется к уже
            // существующему списку исключений интерфейса.
            applyBypassRoutes(tun, bypassFilePath)

            val newSession = OpenVpn3Session(tun)
            // Состояние ядра переводим в SystemState и отдаём слушателю: домен
            // получит его через toConnectionStatus(), который зелёного не вернёт.
            // Сессию передаём явно: по ней [onEngineState] отличит упавший туннель
            // от уже перезапущенного.
            newSession.onState = { state -> onEngineState(newSession, state) }

            // Логи ядра — в logcat, иначе диагностика слепая: без них видно
            // только «туннель не поднялся» без причины.
            //
            // build.type == "debug" — в релизе логи выключены: там строки ядра
            // могут содержать адреса серверов и имена профилей, которым в
            // пользовательском logcat не место. Приватного ключа там не будет:
            // `verb` понижен до 1, а он печатал бы тела PEM только начиная с 3.
            newSession.onLog = { line ->
                if (BuildConfig.DEBUG) android.util.Log.d(TAG, line)
            }

            // Перепроверяем отмену ПЕРЕД подъёмом: отмена могла прийти между
            // чтением и этой строкой, где нет точек приостановки.
            if (connectCancelled) return@launch
            session = newSession
            newSession.start(profileText)
        }
    }

    private fun stopTunnel() {
        // Запоминаем ДО обнуления: иначе проверка «было что останавливать»
        // всегда ложна.
        val wasActive = session != null || connectJob?.isActive == true
        // Гасим уведомление от поздних событий ядра: `session.stop()` ниже может
        // синхронно вернуть Disconnected, и без этого флага в шторке осталась бы
        // карточка уже остановленного туннеля.
        stopped = true
        // Отменяем незавершённое подключение, иначе оно поднимет туннель уже
        // после отключения. Синхронный флаг взводим ДО cancel(): он виден
        // корутине даже там, где точек приостановки нет.
        connectCancelled = true
        connectJob?.cancel()
        connectJob = null
        session?.stop()
        session = null
        // IDLE сообщаем только если было что останавливать: иначе onDestroy
        // после честного FAILED прислал бы IDLE и стёр бы причину отказа.
        if (wasActive) notifyState(SystemState.IDLE)
        // Снимаем карточку сами: сервис живёт до остановки системы, а `stopForeground`
        // здесь не вызывался — уведомление висело бы, обещая связь, которой нет.
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * Исключить из туннеля подсети обхода.
     *
     * Файла нет или строки битые — просто меньше исключений: подключение и
     * защита от этого не страдают. Неудача исключения (API < 33) сообщается
     * экрану отдельно — здесь только факт.
     */
    private fun applyBypassRoutes(tun: VpnServiceTunBuilder, path: String?) {
        if (path == null) return
        val lines = runCatching { File(path).readLines() }.getOrDefault(emptyList())
        for (line in lines) {
            val route = parseBypassCidr(line.trim()) ?: continue
            tun.excludeRoute(route.network, route.prefixLength, ipv6 = false)
        }
    }

    /**
     * Перевод состояния ядра в системное.
     *
     * `Connected` даёт ESTABLISHED, а не зелёное: поднятый интерфейс не
     * доказывает, что трафик идёт через него, и решать это будет `ProtectionGate`.
     * `Disconnected`/`Reconnecting` — это LOST: связь с сервером потеряна,
     * прежний замер больше не описывает действительность.
     */
    private fun onEngineState(source: OpenVpn3Session, state: EngineState) {
        when (state) {
            EngineState.Connecting -> notifyState(SystemState.CONNECTING)
            EngineState.Connected -> notifyState(SystemState.ESTABLISHED)
            EngineState.Reconnecting, EngineState.Disconnected -> notifyState(SystemState.LOST)
            is EngineState.Failed -> notifyState(SystemState.FAILED)
            EngineState.Idle -> notifyState(SystemState.IDLE)
        }

        // Сессия завершилась без нового CONNECT: обнуляем ссылку, иначе мёртвая
        // сессия в поле навсегда заблокирует переподключение — проверка
        // `session != null` в [startTunnel] будет истинной вечно.
        // Сверяем ИМЕННО эту сессию: если пользователь уже начал новый CONNECT,
        // в поле лежит другая, и трогать её нельзя.
        if (state is EngineState.Failed ||
            state == EngineState.Disconnected ||
            state == EngineState.Idle
        ) {
            if (session === source) session = null
        }
    }

    private fun notifyState(state: SystemState) {
        sendBroadcast(
            Intent(ACTION_STATE)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state.name),
        )
        if (!stopped) updateNotification(state)
    }

    /**
     * Привести уведомление в соответствие с состоянием.
     *
     * `startForeground` вызывается один раз при старте, и без этого обновления
     * в шторке навсегда остался бы стартовый текст. В покое уведомление не
     * снимаем через `cancel` — его снимает `stopTunnel` своим
     * `stopForeground(STOP_FOREGROUND_REMOVE)`; здесь достаточно не
     * перерисовывать его текстом «подключено».
     */
    private fun updateNotification(state: SystemState) {
        val text = notificationTextFor(state) ?: return
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun notifyNetworkChanged() {
        sendBroadcast(Intent(ACTION_NETWORK_CHANGED).setPackage(packageName))
    }

    override fun onRevoke() {
        // Система отозвала разрешение VPN: останавливаемся немедленно и честно.
        // Здесь нельзя пытаться «дожать» блокировку — интерфейса уже не будет.
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        // Снимаем callback до остановки туннеля: после смерти сервиса система не
        // должна дёргать его методы.
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Уведомление foreground-сервиса.
     *
     * Без него система убивает сервис, поднятый через `startForegroundService`.
     * Канал создаётся здесь же, а не в приложении: сервис должен уметь показать
     * уведомление, даже если приложение уже выгружено, — а VPN живёт именно так.
     *
     * Текст намеренно без адреса сервера и имени профиля: уведомление видно на
     * экране блокировки, и в нём не должно быть того, что пользователь не готов
     * показывать.
     */
    private fun buildNotification(text: String): android.app.Notification {
        val channelId = NOTIFICATION_CHANNEL_ID
        val manager = getSystemService(NotificationManager::class.java)
        if (manager?.getNotificationChannel(channelId) == null) {
            manager?.createNotificationChannel(
                android.app.NotificationChannel(
                    channelId,
                    "Состояние VPN",
                    // IMPORTANCE_LOW: состояние туннеля — это не событие, ради
                    // которого стоит звонить и вибрировать.
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        return android.app.Notification.Builder(this, channelId)
            .setContentTitle("VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    /** Дескриптор интерфейса, созданный мостом. Закрывается при остановке. */
    internal var tunDescriptor: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_CONNECT = "com.impossi8le.vpnapp.vpnservice.CONNECT"
        const val ACTION_DISCONNECT = "com.impossi8le.vpnapp.vpnservice.DISCONNECT"

        /**
         * Путь к файлу `.ovpn`, а не его текст.
         *
         * Раньше сюда клали содержимое профиля, и приватный ключ уезжал в extras
         * намерения — то есть в поле, видимое системе. Extras не место для
         * секретов, поэтому передаётся путь, а читает файл уже сервис.
         */
        const val EXTRA_PROFILE = "profile"

        /** Путь к файлу со списком обходов (по строке `network/prefix`). */
        const val EXTRA_BYPASS_FILE = "bypass_file"

        // Контракт широковещательных сообщений.
        //
        // ВАЖНО: приёмник живёт в `:core:tunnel` (TunnelStatusReceiver), а
        // этот модуль от него не зависит и зависеть не может — `core:tunnel`
        // сам оборачивает `:vpnservice` (§4.5). Поэтому строки продублированы
        // ЗДЕСЬ, а не взяты из приёмника, и обязаны совпадать с его
        // константами дословно. Разъедется — экран перестанет видеть сервис.
        const val ACTION_STATE = "com.impossi8le.vpnapp.tunnel.STATE"
        const val ACTION_NETWORK_CHANGED = "com.impossi8le.vpnapp.tunnel.NETWORK_CHANGED"
        const val EXTRA_STATE = "state"

        private const val SESSION_NAME = "VPN"
        private const val TAG = "VpnTunnel"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "vpn_status"
        private const val DEFAULT_MTU = 1400
    }
}
