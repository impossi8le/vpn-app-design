package com.impossi8le.vpnapp.feature.bypass

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impossi8le.vpnapp.domain.tunnel.BypassCatalog
import com.impossi8le.vpnapp.domain.tunnel.BypassControl
import com.impossi8le.vpnapp.domain.tunnel.BypassResolve
import com.impossi8le.vpnapp.domain.tunnel.BypassRoute
import com.impossi8le.vpnapp.domain.tunnel.BypassService
import com.impossi8le.vpnapp.domain.tunnel.BypassWrite
import com.impossi8le.vpnapp.domain.tunnel.CustomTarget
import com.impossi8le.vpnapp.domain.tunnel.classifyCustomTarget
import com.impossi8le.vpnapp.domain.tunnel.label
import com.impossi8le.vpnapp.domain.tunnel.mergeRoutes
import com.impossi8le.vpnapp.domain.tunnel.removeRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Состояние экрана «Обходы».
 *
 * Плоская структура, как у [com.impossi8le.vpnapp.feature.account.AccountScreenState]:
 * экран только показывает, решает ViewModel.
 *
 * [servicesFailed] и [routesFailed] — отдельные флаги, а не вывод из пустоты:
 * «сервисов нет» и «каталог не пришёл» — разные вещи, и §6 запрещает выдавать
 * второе за первое. `null` в списках означает «ещё не приходило».
 *
 * [applying] — идёт запись обходов (обход всех РФ-сервисов, свой адрес, удаление).
 * Пока `true`, кнопки заняты: второй тап не должен запускать вторую запись.
 *
 * [supported] — применяет ли ЭТО устройство исключения обходов (API 33+). На
 * API < 33 список записывается, но маршруты не исключаются, и экран не должен
 * называть список «активным». Тот же смысл, что и `bypassSupported` на главном
 * экране (StatusPresentation.kt), — версия приходит снаружи, из MainActivity.
 */
data class BypassUiState(
    val routes: List<BypassRoute>? = null,
    val routesFailed: Boolean = false,
    val catalog: BypassCatalog? = null,
    val servicesFailed: Boolean = false,
    val query: String = "",
    val expandedKeys: Set<String> = emptySet(),
    val customInput: String = "",
    /**
     * Необязательное название своего обхода. Уходит на сервер подписью (`# name`
     * над `route … net_gateway`), поэтому переживает перезапуск приложения.
     * Отдельным полем, а не склейкой с адресом: сервер пишет адрес и подпись в
     * разные строки файла, и склейка сломала бы обе.
     */
    val customName: String = "",
    val applying: Boolean = false,
    val supported: Boolean = true,
    val message: String? = null,
    /**
     * [message] описывает неудачу, а не подтверждение. Несёт тон предупреждения
     * на экране; отдельный флаг, потому что текст сообщения тон не выражает.
     */
    val messageError: Boolean = false,
    /**
     * Сервер ответил `401`: сессия недействительна. Отдельный флаг, а не только
     * текст в [message], потому что экран должен не просто показать неудачу, а
     * прямо позвать войти заново — иначе пользователь будет повторять запись,
     * которая будет отвергнута столько же раз.
     */
    val unauthenticated: Boolean = false,
)

/**
 * Экран «Обходы».
 *
 * Все записи идут одним путём: взять текущий список, изменить его, записать
 * ЦЕЛИКОМ (`/app/bypass/set`), перечитать с сервера. Перечитываем не только
 * потому, что сервер переписывает файл — но и чтобы не показать как «активный»
 * список, которого мы не получали (§6).
 */
class BypassViewModel(
    private val control: BypassControl,
    supported: Boolean,
) : ViewModel() {

    private val _state = MutableStateFlow(BypassUiState(supported = supported))
    val state: StateFlow<BypassUiState> = _state.asStateFlow()

    /** Первичная загрузка. Из конструктора — состояния туннеля не касается. */
    fun start() {
        if (_state.value.catalog != null || _state.value.servicesFailed) return
        refresh()
        loadCatalog()
    }

    /** Перечитать список обходов и каталог с сервера. */
    fun refresh() {
        viewModelScope.launch { reloadRoutes() }
    }

    private fun loadCatalog() {
        viewModelScope.launch {
            when (val catalog = control.catalog()) {
                is BypassCatalog.Loaded ->
                    _state.value = _state.value.copy(catalog = catalog, servicesFailed = false)

                BypassCatalog.Failed ->
                    // Не подменяем неудачу пустым списком: экран скажет словами,
                    // что каталог не пришёл.
                    _state.value = _state.value.copy(catalog = null, servicesFailed = true)
            }
        }
    }

    private suspend fun reloadRoutes() {
        val routes = control.fetchRoutes()
        _state.value = if (routes == null) {
            _state.value.copy(routesFailed = true)
        } else {
            _state.value.copy(routes = routes, routesFailed = false)
        }
    }

    /** Показать/скрыть домены одного сервиса. */
    fun toggleExpanded(key: String) {
        val expanded = _state.value.expandedKeys
        _state.value = _state.value.copy(
            expandedKeys = if (key in expanded) expanded - key else expanded + key,
        )
    }

    fun onQueryChange(text: String) {
        _state.value = _state.value.copy(query = text)
    }

    fun onCustomInputChange(text: String) {
        _state.value = _state.value.copy(customInput = text, message = null)
    }

    /** Название своего обхода. Пусто — записи имени нет, в списке будет голый CIDR. */
    fun onCustomNameChange(text: String) {
        _state.value = _state.value.copy(customName = text, message = null)
    }

    /** Скрыть сообщение вручную (например, после удачной записи). */
    fun clearMessage() {
        _state.value = _state.value.copy(
            message = null,
            messageError = false,
            unauthenticated = false,
        )
    }

    /**
     * «Обойти все РФ сервисы».
     *
     * Разбираем домены КАЖДОГО сервиса отдельно и подписываем подсети названием
     * сервиса, добавляя результат к тому, что уже записано. Один общий запрос был
     * бы короче, но вернул бы CIDR без принадлежности — в списке «Обходы сейчас»
     * тогда не отличить `155.212.204.0/24` от чужого адреса.
     *
     * Пустой каталог — честная причина, а не тихая удача.
     */
    fun bypassAll() {
        val catalog = _state.value.catalog as? BypassCatalog.Loaded
        if (catalog == null) {
            fail("Список сервисов не загружен")
            return
        }
        val services = catalog.services.filter { service ->
            service.domains.any { it.trim().isNotEmpty() }
        }
        if (services.isEmpty()) {
            fail("В списке сервисов нет доменов")
            return
        }
        apply { applyAllServices(services) }
    }

    /**
     * Разобрать и записать все сервисы поштучно, чтобы каждый CIDR нёс название
     * своего сервиса.
     *
     * Имя — НАЗВАНИЕ сервиса («ВКонтакте»), а не домен: домен уже виден рядом в
     * каталоге, а в списке «Обходы сейчас» место принадлежности, не адреса.
     *
     * Сервер отвергает ВЕСЬ запрос из-за одной негодной цели, поэтому на отказе
     * любого сервиса останавливаемся и ничего не пишем — частичный обход выдал бы
     * себя за полный.
     */
    private suspend fun applyAllServices(services: List<BypassService>) {
        val accumulated = mutableListOf<BypassRoute>()
        for (service in services) {
            val targets = service.domains.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (targets.isEmpty()) continue
            when (val resolved = control.resolve(targets)) {
                is BypassResolve.Resolved ->
                    accumulated += resolved.routes.map { it.copy(name = service.title) }

                BypassResolve.InvalidTarget -> {
                    fail("Сервер отклонил адреса сервиса «${service.title}»")
                    return
                }

                // Сессия недействительна — ни повторять, ни переписывать адреса
                // не поможет. Прямо зовём войти заново.
                BypassResolve.NotAuthorized -> {
                    needSignIn()
                    return
                }

                BypassResolve.Failed -> {
                    fail("Не удалось разобрать адреса сервисов")
                    return
                }
            }
        }
        if (accumulated.isEmpty()) {
            fail("Не удалось разобрать адреса сервисов")
            return
        }
        val merged = mergeRoutes(control.fetchRoutes().orEmpty(), accumulated)
        writeAndReload(merged) { "Обход включён: сервисов — ${services.size}" }
    }

    /**
     * Добавить свой обход.
     *
     * Домен уходит на сервер как есть; CIDR проверяется локально (широкий
     * префикс — не подсеть, а ошибка). При `400` — прямо говорим, что адрес
     * негоден или слишком широк, и НИЧЕГО не добавляем локально.
     *
     * Необязательное название едет подписью записи: сервер пишет его
     * `#`-строкой над маршрутом, и список показывает имя вместо голого CIDR.
     */
    fun addCustom() {
        val parsed = classifyCustomTarget(_state.value.customInput, _state.value.customName)
        when (parsed) {
            CustomTarget.Blank -> fail("Введите домен или подсеть")

            CustomTarget.InvalidCidr ->
                fail("Неверная подсеть — проверьте адрес и префикс (не шире /8)")

            is CustomTarget.Valid -> apply {
                when (val resolved = control.resolve(listOf(parsed.target))) {
                    is BypassResolve.Resolved -> {
                        val named = resolved.routes.map { it.copy(name = parsed.name) }
                        val merged = mergeRoutes(control.fetchRoutes().orEmpty(), named)
                        writeAndReload(merged) { "Добавлено: ${parsed.name ?: parsed.target}" }
                    }

                    BypassResolve.InvalidTarget ->
                        fail("Адрес неверен или слишком широк — укажите точнее")

                    BypassResolve.NotAuthorized -> needSignIn()

                    BypassResolve.Failed -> fail("Не удалось проверить адрес")
                }
            }
        }
    }

    /** Удалить одну подсеть: пишем оставшийся список целиком. */
    fun remove(route: BypassRoute) {
        val current = _state.value.routes
        if (current == null) {
            fail("Список не загружен")
            return
        }
        apply { writeAndReload(removeRoute(current, route)) { "Удалено: ${route.label()}" } }
    }

    /**
     * Общая обвязка записи: флаг занятости, честный исход, всегда снятый флаг.
     *
     * Второй вызов, пока идёт первый, отбрасывается: две записи поверх друг друга
     * дали бы список, которого пользователь не заказывал.
     */
    private fun apply(block: suspend () -> Unit) {
        if (_state.value.applying) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                applying = true,
                message = null,
                messageError = false,
                unauthenticated = false,
            )
            try {
                block()
            } finally {
                _state.value = _state.value.copy(applying = false)
            }
        }
    }

    /** Записать и перечитать. [success] даёт текст сообщения о состоявшейся записи. */
    private suspend fun writeAndReload(
        routes: List<BypassRoute>,
        success: () -> String,
    ) {
        when (val write = control.set(routes)) {
            is BypassWrite.Applied -> {
                // Перечитываем с сервера: показываем то, что действительно записано,
                // а не то, что мы надеялись записать.
                reloadRoutes()
                _state.value = _state.value.copy(message = success(), messageError = false)
            }

            BypassWrite.Invalid ->
                fail("Сервер отверг список — ничего не записано")

            // Записи не было: сессия недействительна. Не говорим «повторите» —
            // повтор будет отвергнут так же. Зовём войти заново.
            BypassWrite.NotAuthorized -> needSignIn()

            BypassWrite.Failed -> fail("Не удалось сохранить обходы")
        }
    }

    /** Сообщение о неудаче: тот же [BypassUiState.message], но с тоном предупреждения. */
    private fun fail(text: String) {
        _state.value = _state.value.copy(
            message = text,
            messageError = true,
            unauthenticated = false,
        )
    }

    /**
     * Сессия недействительна (`401`). Записи НЕ было, и повтор её не спасёт:
     * сообщение говорит прямо, и [BypassUiState.unauthenticated] зовёт экран
     * предложить вход заново.
     */
    private fun needSignIn() {
        _state.value = _state.value.copy(
            message = "Сессия недействительна — войдите заново",
            messageError = true,
            unauthenticated = true,
        )
    }
}
