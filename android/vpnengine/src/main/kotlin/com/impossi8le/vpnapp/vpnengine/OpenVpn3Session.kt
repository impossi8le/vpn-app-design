package com.impossi8le.vpnapp.vpnengine

import com.impossi8le.vpnapp.domain.tunnel.CoreConfig
import com.impossi8le.vpnapp.domain.tunnel.ProfileSanitizer
import net.openvpn.ovpn3.ClientAPI_Config
import net.openvpn.ovpn3.ClientAPI_EvalConfig
import net.openvpn.ovpn3.ClientAPI_Event
import net.openvpn.ovpn3.ClientAPI_LogInfo
import net.openvpn.ovpn3.ClientAPI_OpenVPNClient
import net.openvpn.ovpn3.ClientAPI_OpenVPNClientHelper
import net.openvpn.ovpn3.ClientAPI_Status
import net.openvpn.ovpn3.DnsOptions
import net.openvpn.ovpn3.DnsServer

/**
 * Состояние сессии, как его понимает наше приложение.
 *
 * Намеренно НЕ `ConnectionStatus` из домена: движок ничего не знает о защите и
 * не имеет права сообщать что-либо о ней. Перевод в состояние экрана делает
 * `core:tunnel`, где и живёт маппинг (§6). Если бы движок умел вернуть
 * `.Protected`, инвариант держался бы на честном слове, а не на типах.
 */
sealed interface EngineState {
    data object Idle : EngineState
    data object Connecting : EngineState
    data object Connected : EngineState
    data object Reconnecting : EngineState
    data object Disconnected : EngineState
    data class Failed(val reason: String, val fatal: Boolean) : EngineState
}

/**
 * Сессия ядра OpenVPN 3.
 *
 * Три вещи сделаны именно так и по делу:
 *
 * 1. **Текст профиля проходит [ProfileSanitizer].** У `ClientAPI_Config` нет
 *    поля уровня логирования (проверено по сгенерированному классу: сеттеров —
 *    `setContent`, `setProtoOverride`, `setCompressionMode` и подобные, `verb`
 *    среди них нет). Ядро читает `verb` ИЗ ТЕКСТА, а боевой профиль просит
 *    `verb 3` — это печать тел PEM, то есть приватного ключа, в logcat.
 *
 * 2. **Клиента наследуем, а не оборачиваем.** В SWIG включены директора
 *    (`directors="1"`), поэтому `event` и `log` вызываются из C++ в Java через
 *    виртуальную таблицу. Композиция тут не сработала бы.
 *
 * 3. **Клиент сам является мостом к `VpnService`**: `ClientAPI_OpenVPNClient`
 *    наследует `ClientAPI_TunBuilderBase`, поэтому `tun_builder_*`
 *    переопределяются здесь и делегируют в [TunBridge], а не создаётся
 *    отдельный объект-мост.
 */
class OpenVpn3Session(
    private val tun: TunBridge,
    private val coreConfig: CoreConfig = CoreConfig.fromProfile(null),
) {

    private var client: EngineClient? = null

    /** Куда сообщать о состоянии. Ставится до [start]. */
    var onState: ((EngineState) -> Unit)? = null

    /** Строки лога ядра. Уже без ключей: уровень понижен до безопасного. */
    var onLog: ((String) -> Unit)? = null

    /**
     * Разобрать профиль, ничего не поднимая.
     *
     * Нужно до подключения: о негодном профиле пользователь должен узнать
     * сразу, а не после попытки соединения.
     */
    fun evaluate(profileText: String): ProfileEvaluation {
        val config = ClientAPI_Config().apply {
            setContent(ProfileSanitizer.sanitizeVerb(profileText).text)
        }

        val eval: ClientAPI_EvalConfig = try {
            helper.eval_config(config)
        } catch (e: Exception) {
            // Ядро может бросить на битом профиле: это ожидаемый исход, а не сбой.
            return ProfileEvaluation(
                valid = false,
                error = e.message ?: "профиль не разобран",
                serverHost = "",
                serverPort = "",
                protocol = "",
                requiresPassword = false,
            )
        }

        return ProfileEvaluation(
            valid = !eval.getError(),
            error = eval.getMessage().takeIf { eval.getError() },
            serverHost = eval.getRemoteHost(),
            serverPort = eval.getRemotePort(),
            protocol = eval.getRemoteProto(),
            requiresPassword = eval.getPrivateKeyPasswordRequired(),
        )
    }

    /**
     * Поднять туннель.
     *
     * **Блокирующий вызов.** `connect()` возвращается, когда соединение
     * завершилось или провалилось: пока туннель жив, вызов не возвращается.
     * Поэтому его зовут из корутины на IO, и поэтому `Connected` ставит
     * обработчик событий, а не код после вызова.
     */
    fun start(profileText: String) {
        if (client != null) return

        // Правка уровня логирования — ДО передачи ядру. Иначе ключ уйдёт в лог.
        val sanitized = ProfileSanitizer.sanitizeVerb(profileText)
        if (sanitized.wasUnsafe) {
            onLog?.invoke(
                "Уровень логирования из профиля понижен: verb ${sanitized.originalVerb} печатал бы тела PEM",
            )
        }

        val config = ClientAPI_Config().apply {
            setContent(sanitized.text)
        }

        val newClient = EngineClient(tun).also {
            it.onState = { state -> onState?.invoke(state) }
            it.onLog = { line -> onLog?.invoke(line) }
        }
        client = newClient

        onState?.invoke(EngineState.Connecting)

        val eval = helper.eval_config(config)
        if (eval.getError()) {
            onState?.invoke(EngineState.Failed(eval.getMessage(), fatal = true))
            client = null
            return
        }

        val status: ClientAPI_Status = newClient.connect()
        if (status.getError()) {
            onState?.invoke(EngineState.Failed(status.getMessage(), fatal = true))
        }
    }

    fun stop() {
        runCatching { client?.stop() }
        client = null
        onState?.invoke(EngineState.Disconnected)
    }

    private companion object {
        val helper = ClientAPI_OpenVPNClientHelper()
    }
}

/** Разбор профиля: то, что можно показать, но без ключей. */
data class ProfileEvaluation(
    val valid: Boolean,
    val error: String?,
    val serverHost: String,
    val serverPort: String,
    val protocol: String,
    val requiresPassword: Boolean,
)

/**
 * Клиент ядра с перехватом событий, логов и запросов к интерфейсу.
 *
 * Наследование обязательно: ядро вызывает `event` и `log` из C++ через JNI.
 */
private class EngineClient(private val tun: TunBridge) : ClientAPI_OpenVPNClient() {

    var onState: ((EngineState) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    // --- события ядра --------------------------------------------------------
    //
    // ВАЖНО: отдельных методов вроде `getConnected()` НЕ существует. В
    // `ClientAPI_Event` есть только `getName`, `getInfo`, `getError` и
    // `getFatal` (проверено по сгенерированному классу). Само событие
    // различается СТРОКОЙ из `getName()` — имена приходят из ядра.

    override fun event(event: ClientAPI_Event) {
        val name = event.getName().orEmpty()
        val info = event.getInfo().orEmpty()

        val state: EngineState? = when {
            // `fatal` означает разрыв соединения, `error` — некритичный сбой:
            // разница важна для пользователя, поэтому не смешиваем.
            event.getFatal() -> EngineState.Failed(info.ifBlank { name }, fatal = true)
            event.getError() -> EngineState.Failed(info.ifBlank { name }, fatal = false)

            name.contains(EVENT_CONNECTED) -> EngineState.Connected
            name.contains(EVENT_RECONNECTING) -> EngineState.Reconnecting
            name.contains(EVENT_DISCONNECTED) -> EngineState.Disconnected
            // Остальные имена (CONNECTING, GET_CONFIG, ASSIGN_IP, ADD_ROUTES,
            // RESOLVE, WAIT) — промежуточные шаги. Отдельных состояний для них
            // нет намеренно: пользователю важно «идёт процесс», а не его фаза.
            else -> null
        }

        state?.let { onState?.invoke(it) }
    }

    override fun log(loginfo: ClientAPI_LogInfo?) {
        val text = loginfo?.getText() ?: return
        if (text.isNotBlank()) onLog?.invoke(text)
    }

    // --- мост к VpnService ---------------------------------------------------
    // Сигнатуры совпадают с `ClientAPI_TunBuilderBase` один в один: иначе
    // `override` не скомпилируется. Клиент сам наследует этот базовый класс,
    // поэтому ядро зовёт методы напрямую.

    override fun tun_builder_new(): Boolean = tun.new()

    override fun tun_builder_set_mtu(mtu: Int): Boolean = tun.setMtu(mtu)

    override fun tun_builder_add_address(
        address: String?,
        prefixLength: Int,
        gateway: String?,
        ipv6: Boolean,
        net30: Boolean,
    ): Boolean = tun.addAddress(address.orEmpty(), prefixLength, ipv6)

    override fun tun_builder_add_route(
        address: String?,
        prefixLength: Int,
        metric: Int,
        ipv6: Boolean,
    ): Boolean = tun.addRoute(address.orEmpty(), prefixLength, ipv6)

    override fun tun_builder_reroute_gw(ipv4: Boolean, ipv6: Boolean, flags: Long): Boolean =
        tun.rerouteGw(ipv4, ipv6, flags)

    /**
     * DNS из профиля.
     *
     * `getServers()` возвращает не список, а карту `DnsOptions_ServersMap`:
     * ключ — номер сервера, значение — `DnsServer`. У сервера адреса лежат в
     * списке `getAddresses()`, и уже там каждый элемент — `DnsAddress` со
     * строкой. Такая вложенность снята с реально сгенерированных классов, а не
     * угадана: первая версия этого метода считала, что серверы — список.
     */
    override fun tun_builder_set_dns_options(dns: DnsOptions?): Boolean {
        val servers = dns?.getServers() ?: return true

        var ok = true
        for (entry in servers.entrySet()) {
            val server: DnsServer = entry.value ?: continue
            val addresses = server.getAddresses() ?: continue
            for (i in 0 until addresses.size()) {
                val address = runCatching { addresses.get(i)?.getAddress() }.getOrNull()
                if (!address.isNullOrBlank()) ok = tun.addDns(address) && ok
            }
        }
        return ok
    }

    override fun tun_builder_set_allow_local_dns(allow: Boolean): Boolean =
        tun.setAllowLocalDns(allow)

    override fun tun_builder_establish(): Int = tun.establish()

    override fun tun_builder_persist(): Boolean = tun.persist()

    override fun tun_builder_teardown(disconnect: Boolean) = tun.teardown(disconnect)

    // --- защита сокета -------------------------------------------------------
    /**
     * Критично для отсутствия утечек: без этого сокет ядра может уйти мимо
     * туннеля. На Android за этим стоит `VpnService.protect(fd)`.
     *
     * Реализация — в [TunBridge.protectSocket]. Заглушка, всегда возвращающая
     * `true`, была бы опаснее отказа: ядро считало бы, что сокет защищён.
     */
    override fun socket_protect(socket: Int, remote: String?, ipv6: Boolean): Boolean =
        tun.protectSocket(socket)
}

/**
 * Имена событий ядра.
 *
 * Сравниваются через `contains`: ядро присылает их в виде, который может нести
 * уточнение, и точное равенство строки хрупко. Константы вынесены, чтобы смысл
 * был виден в коде, а не терялся в строках-литералах.
 */
private const val EVENT_CONNECTED = "CONNECTED"
private const val EVENT_RECONNECTING = "RECONNECTING"
private const val EVENT_DISCONNECTED = "DISCONNECTED"
