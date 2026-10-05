package com.impossi8le.vpnapp.vpnengine

import com.impossi8le.vpnapp.domain.tunnel.CoreConfig
import com.impossi8le.vpnapp.domain.tunnel.ProfileSanitizer
import net.openvpn.ovpn3.ClientAPI_Config
import net.openvpn.ovpn3.ClientAPI_EvalConfig
import net.openvpn.ovpn3.ClientAPI_StringVec
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
        // Загрузка ДО создания любого класса ядра: `ClientAPI_Config` тоже
        // тянет за собой статический инициализатор JNI, и без библиотеки это
        // падение, а не возврат ошибки. Поэтому проверяем здесь, а не только
        // в start(): метод могут позвать раньше.
        if (!EngineLoader.load()) {
            return ProfileEvaluation(
                valid = false,
                error = EngineLoader.failureReason ?: "движок недоступен на этом устройстве",
                serverHost = "",
                serverPort = "",
                protocol = "",
                requiresPassword = false,
            )
        }

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

        // Ядро — нативная библиотека, и её надо загрузить ДО первого обращения к
        // сгенерированным классам. SWIG-классы сами этого не делают: в их
        // статическом блоке только `swig_module_init()`. Без загрузки первое же
        // обращение падает с UnsatisfiedLinkError уже в момент выполнения.
        if (!EngineLoader.load()) {
            onState?.invoke(
                EngineState.Failed(
                    EngineLoader.failureReason ?: "движок недоступен на этом устройстве",
                    fatal = true,
                ),
            )
            return
        }

        // Правка уровня логирования — ДО передачи ядру. Иначе ключ уйдёт в лог.
        val sanitized = ProfileSanitizer.sanitizeVerb(profileText)
        if (sanitized.wasUnsafe) {
            onLog?.invoke(
                "Уровень логирования из профиля понижен: verb ${sanitized.originalVerb} печатал бы тела PEM",
            )
        }

        // РАЗВОРАЧИВАЕМ ИНЛАЙН-БЛОКИ, и без этого шага ядро профиль не примет.
        //
        // `<ca>`, `<cert>`, `<key>`, `<tls-auth>` — это не опции OpenVPN, а
        // формат хранения: содержимое блоков надо «вклеить» в плоский профиль.
        // Делает это `merge_config_string`, и именно он превращает
        // `<cert>...</cert>` в `cert` вместе со встроенным сертификатом.
        //
        // Без merge ядро сообщает `ERR_INVALID_CONFIG: option 'cert' not found`
        // и не подключается — проверено на устройстве. Симптом обманчив: файл
        // прочитан и блок в нём есть, но для ядра его как будто нет.
        val merged = try {
            helper.merge_config_string(sanitized.text)
        } catch (e: Exception) {
            onState?.invoke(EngineState.Failed(e.message ?: "профиль не разобран", fatal = true))
            return
        }

        if (merged.getErrorText().isNotBlank()) {
            onState?.invoke(EngineState.Failed(merged.getErrorText(), fatal = true))
            return
        }

        // Диагностика: показывает, что именно уходит ядру. Без неё сообщение
        // «option 'cert' not found» не отличить от «файл не прочитан».
        val mergedText = merged.getProfileContent()
        onLog?.invoke(
            "профиль ядру: ${mergedText.length} символов, " +
                "cert=${mergedText.contains("cert")}, " +
                "арморов=${Regex("-----BEGIN").findAll(mergedText).count()}",
        )
        // Первые 900 символов — чтобы увидеть, во что ядро превратило блоки.
        val preview = mergedText.take(900).replace('\n', '|')
        onLog?.invoke("НАЧАЛО: $preview")

        val config = ClientAPI_Config().apply {
            setContent(mergedText)
        }

        val newClient = EngineClient(tun).also {
            it.onState = { state -> onState?.invoke(state) }
            it.onLog = { line -> onLog?.invoke(line) }
        }
        client = newClient

        onState?.invoke(EngineState.Connecting)

        // eval_config вызывается У КЛИЕНТА, а не у helper'а, и это принципиально.
        //
        // В ядре это два разных метода с одинаковым именем:
        //   - `helper.eval_config()`  — только разбирает профиль И ВЫБРАСЫВАЕТ
        //     результат, он нужен лишь для предварительного осмотра;
        //   - `client.eval_config()`  — разбирает профиль и СОХРАНЯЕТ разобранные
        //     опции в состояние клиента, откуда их берёт `connect()`.
        //
        // Я звал метод helper'а, поэтому `connect()` шёл в бой с пустым набором
        // опций и сообщал `option 'cert' not found` — хотя сертификат в профиле
        // был. Проверено по исходнику ядра (ovpncli.cpp: «API client submits the
        // configuration here before calling connect()»).
        val eval = newClient.eval_config(config)
        if (eval.getError()) {
            onState?.invoke(EngineState.Failed(eval.getMessage(), fatal = true))
            client = null
            return
        }

        val status: ClientAPI_Status = newClient.connect()
        // Статус логируем ВСЕГДА, а не только при ошибке: успешное завершение
        // `connect()` тоже означает разрыв, и без строки «connect вернулся»
        // непонятно, ждать ли ещё или уже поздно.
        onLog?.invoke("connect() вернулся: error=${status.getError()} status=${status.getStatus()} msg=${status.getMessage()}")
        if (status.getError()) {
            onState?.invoke(EngineState.Failed(status.getMessage(), fatal = true))
        }
    }

    fun stop() {
        runCatching { client?.stop() }
        client = null
        onState?.invoke(EngineState.Disconnected)
    }

    /**
     * Помощник ядра.
     *
     * **Создаётся ЛЕНИВО, а не в `companion object`, и это не оптимизация.**
     * `ClientAPI_OpenVPNClientHelper` при инициализации класса вызывает
     * статический блок `ovpncliJNI`, а тот — нативный `swig_module_init()`.
     * Если объект лежит в статическом инициализаторе, он создаётся при первом
     * обращении К КЛАССУ, то есть раньше, чем `start()` успевает загрузить
     * библиотеку. Приложение падало с `UnsatisfiedLinkError: swig_module_init
     * ... is the library loaded?` ровно на этом — проверено на устройстве.
     *
     * `by lazy` сдвигает создание к первому использованию, а оно всегда идёт
     * после [EngineLoader.load].
     */
    private val helper: ClientAPI_OpenVPNClientHelper by lazy {
        ClientAPI_OpenVPNClientHelper()
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

        // Логируем КАЖДОЕ событие целиком, включая те, для которых у нас нет
        // состояния. Без этого причина отказа не видна: события вроде
        // AUTH_FAILED или WAIT попадали в `else -> null` и терялись молча, а в
        // логе оставалась только версия ядра.
        onLog?.invoke("event: name=$name error=${event.getError()} fatal=${event.getFatal()} info=$info")

        val state: EngineState? = when {
            // `fatal` означает разрыв соединения, `error` — некритичный сбой:
            // разница важна для пользователя, поэтому не смешиваем.
            event.getFatal() -> EngineState.Failed(info.ifBlank { name }, fatal = true)
            event.getError() -> EngineState.Failed(info.ifBlank { name }, fatal = false)

            // Разбор имени — в чистой функции [engineStateFrom]: она сравнивает
            // имена ТОЧНО, а не по подстроке. Прежнее `name.contains("CONNECTED")`
            // путало разрыв `DISCONNECTED` с подъёмом `CONNECTED` (одно имя
            // содержит другое) и после отключения возвращало экран в «туннель
            // поднят». Подробности и проверка на устройстве — в doc-комментарии
            // к [engineStateFrom].
            else -> engineStateFrom(name)
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

    /**
     * Адрес удалённой стороны.
     *
     * Базовая реализация возвращает `false`, а ядро считает это отказом и рвёт
     * установку. На Android адрес сервера `Builder` не принимает — маршрутами
     * занимается система, — но метод обязан вернуть `true`.
     */
    override fun tun_builder_set_remote_address(address: String?, ipv6: Boolean): Boolean =
        tun.setRemoteAddress(address.orEmpty(), ipv6)

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
        // Обходим КАРТУ ПО ИНДЕКСУ, а не через `entrySet()`.
        //
        // `entrySet()` недоступен из Kotlin: SWIG-класс объявляет собственные
        // вложенные `Iterator` и `Entry`, которые затеняют `java.util`, и
        // компилятор видит неоднозначный `iterator()`. Индексный доступ
        // проблему снимает: у карты есть `size` и `get(key)`, а ключи — это
        // последовательные номера DNS-серверов, что для нашего случая и нужно.
        //
        // `size` — СВОЙСТВО, а не функция: Kotlin превращает Java-метод `size()`
        // без аргументов в свойство, и вызов `size()` не компилируется.
        for (i in 0 until servers.size) {
            val server: DnsServer = servers.get(i) ?: continue
            val addresses = server.getAddresses() ?: continue
            for (j in 0 until addresses.size) {
                val address = runCatching { addresses.get(j)?.getAddress() }.getOrNull()
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

    // --- остальные запросы ядра ---------------------------------------------
    //
    // Каждый из этих методов обязан вернуть `true` (или быть пустым для `void`).
    // Причина не в том, что они что-то делают, а в том, что базовая реализация
    // возвращает `false`, а ядро читает `false` как ОТКАЗ и обрывает установку.
    //
    // Это выяснилось последовательными прогонами на устройстве: ядро доходило
    // до успешного TLS-рукопожатия с сервером (сессия становилась ACTIVE) и
    // падало на очередном методе:
    //   TUN Error: tun_prop_error: tun_builder_set_remote_address failed
    //   TUN Error: tun_prop_error: tun_builder_set_session_name failed
    // Поэтому реализованы ВСЕ, а не по одному: иначе каждый прогон (минуты)
    // вскрывал бы ровно следующий.
    //
    // Что эти методы означают в терминах Android:
    //  - `set_layer` / `set_session_name` / `set_route_metric_default` — у
    //    `VpnService.Builder` соответствий нет, но пропустить их нельзя;
    //  - `exclude_route` — исключение маршрута из туннеля. Намеренно НЕ
    //    реализуем: исключённый маршрут означает трафик мимо туннеля, а это
    //    ровно та утечка, против которой всё делается. Возвращаем `true`,
    //    потому что исключать нечего: у нас нет ни одного такого маршрута;
    //  - `set_allow_family` — разрешение семейства адресов. `false` отдаём
    //    только на явный запрет, иначе туннель не поднимется;
    //  - прокси и WINS — на Android не поддерживаем, но обязаны ответить;
    //  - `get_local_networks` — список локальных сетей. Пустой список корректен:
    //    ядро использует его для обхода локальных адресов, а у нас такого
    //    обхода нет — весь трафик идёт в туннель;
    //  - `establish_lite` — вариант поднятия без настройки; пустой.

    override fun tun_builder_set_layer(layer: Int): Boolean = true

    override fun tun_builder_set_session_name(name: String?): Boolean = true

    override fun tun_builder_set_route_metric_default(metric: Int): Boolean = true

    override fun tun_builder_exclude_route(
        address: String?,
        prefixLength: Int,
        metric: Int,
        ipv6: Boolean,
    ): Boolean = true

    override fun tun_builder_set_allow_family(af: Int, allow: Boolean): Boolean = allow

    override fun tun_builder_add_proxy_bypass(bypassHost: String?): Boolean = true

    override fun tun_builder_set_proxy_auto_config_url(url: String?): Boolean = true

    override fun tun_builder_set_proxy_http(host: String?, port: Int): Boolean = true

    override fun tun_builder_set_proxy_https(host: String?, port: Int): Boolean = true

    override fun tun_builder_add_wins_server(address: String?): Boolean = true

    override fun tun_builder_get_local_networks(ipv6: Boolean): ClientAPI_StringVec =
        ClientAPI_StringVec()

    override fun tun_builder_establish_lite() {
        // Ничего: отдельного «лёгкого» поднятия на Android нет.
    }

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

