package com.impossi8le.vpnapp.feature.bypass

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impossi8le.vpnapp.domain.tunnel.BypassCatalog
import com.impossi8le.vpnapp.domain.tunnel.BypassControl
import com.impossi8le.vpnapp.domain.tunnel.BypassResolve
import com.impossi8le.vpnapp.domain.tunnel.BypassRoute
import com.impossi8le.vpnapp.domain.tunnel.BypassWrite
import com.impossi8le.vpnapp.domain.tunnel.CustomTarget
import com.impossi8le.vpnapp.domain.tunnel.bypassTargets
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
    val applying: Boolean = false,
    val supported: Boolean = true,
    val message: String? = null,
    /**
     * [message] описывает неудачу, а не подтверждение. Несёт тон предупреждения
     * на экране; отдельный флаг, потому что текст сообщения тон не выражает.
     */
    val messageError: Boolean = false,
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

    /** Скрыть сообщение вручную (например, после удачной записи). */
    fun clearMessage() {
        _state.value = _state.value.copy(message = null, messageError = false)
    }

    /**
     * «Обойти все РФ сервисы».
     *
     * Разбираем ВСЕ домены каталога одним запросом и добавляем результат к тому,
     * что уже записано. Пустой каталог — честная причина, а не тихая удача.
     */
    fun bypassAll() {
        val catalog = _state.value.catalog as? BypassCatalog.Loaded
        if (catalog == null) {
            fail("Список сервисов не загружен")
            return
        }
        val targets = bypassTargets(catalog.services)
        if (targets.isEmpty()) {
            fail("В списке сервисов нет доменов")
            return
        }
        apply {
            when (val resolved = control.resolve(targets)) {
                is BypassResolve.Resolved -> {
                    val merged = mergeRoutes(control.fetchRoutes().orEmpty(), resolved.routes)
                    writeAndReload(merged) { "Обход включён: сервисов — ${resolved.routes.size}" }
                }

                // Сервер отверг часть доменов каталога. Это не вина пользователя,
                // и молчать нельзя: он нажал «обойти все», а обход не включился.
                BypassResolve.InvalidTarget -> fail("Сервер отклонил адреса сервисов")

                BypassResolve.Failed -> fail("Не удалось разобрать адреса сервисов")
            }
        }
    }

    /**
     * Добавить свой обход.
     *
     * Домен уходит на сервер как есть; CIDR проверяется локально (широкий
     * префикс — не подсеть, а ошибка). При `400` — прямо говорим, что адрес
     * негоден или слишком широк, и НИЧЕГО не добавляем локально.
     */
    fun addCustom() {
        when (val target = classifyCustomTarget(_state.value.customInput)) {
            CustomTarget.Blank -> fail("Введите домен или подсеть")

            CustomTarget.InvalidCidr ->
                fail("Неверная подсеть — проверьте адрес и префикс (не шире /8)")

            is CustomTarget.Valid -> apply {
                when (val resolved = control.resolve(listOf(target.target))) {
                    is BypassResolve.Resolved -> {
                        val merged = mergeRoutes(control.fetchRoutes().orEmpty(), resolved.routes)
                        writeAndReload(merged) { "Добавлено: ${target.target}" }
                    }

                    BypassResolve.InvalidTarget ->
                        fail("Адрес неверен или слишком широк — укажите точнее")

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
            _state.value = _state.value.copy(applying = true, message = null, messageError = false)
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

            BypassWrite.Failed -> fail("Не удалось сохранить обходы")
        }
    }

    /** Сообщение о неудаче: тот же [BypassUiState.message], но с тоном предупреждения. */
    private fun fail(text: String) {
        _state.value = _state.value.copy(message = text, messageError = true)
    }
}
