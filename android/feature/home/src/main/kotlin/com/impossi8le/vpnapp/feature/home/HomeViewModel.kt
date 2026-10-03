package com.impossi8le.vpnapp.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionGate
import com.impossi8le.vpnapp.domain.protection.ReverificationPolicy
import com.impossi8le.vpnapp.domain.tunnel.TunnelControlling
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Состояние главного экрана.
 *
 * Экран держит ДВА независимых источника и не смешивает их в одном поле:
 *
 *  - [system] — что говорит туннель (поднят, соединяется, отключён);
 *  - [measured] — что показал последний замер защиты.
 *
 * Раньше здесь было одно поле, в которое писали оба источника, и они гонялись:
 * отложенный эмит туннеля мог затереть вердикт гейта, и зелёное мигало.
 *
 * Ключевое свойство: **любое новое состояние туннеля обнуляет замер.** Замер
 * описывал прежнее состояние сети; после смены он недействителен, и зелёное не
 * должно его пережить. Это требование §6, а не оптимизация.
 *
 * [status] обновляется явно, а не через `stateIn(viewModelScope, Eagerly)`.
 * `stateIn` заводит корутину в `viewModelScope`, которая живёт до `onCleared()`
 * и не отменяется вместе с наблюдением — то есть остаётся висеть после [stop].
 * Здесь этого нет: после [stop] не остаётся ни одной живой корутины.
 *
 * Зависимость на [ProtectionGate], а не на пробу, не даёт этому классу собрать
 * `Protected` руками: `ProtectionEvidence` вне `core:domain` не конструируется.
 */
class HomeViewModel(
    private val tunnel: TunnelControlling,
    private val gate: ProtectionGate,
    policy: ReverificationPolicy = ReverificationPolicy.Default,
) : ViewModel() {

    private val system = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)

    /** Результат последнего замера. `null` — замера для текущего состояния нет. */
    private val measured = MutableStateFlow<ConnectionStatus?>(null)

    /**
     * Поколение состояния туннеля.
     *
     * Замер описывает сеть, в которой он сделан. Растёт при каждом эмите
     * туннеля; замер запоминает, на каком поколении он снят, и признаётся
     * действительным, только пока поколение не сменилось. Это выражает
     * «замер устарел» напрямую, вместо неявных правил приоритета.
     */
    private var generation = 0
    private var measuredGeneration = -1

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val watcher = ProtectionWatcher(gate, policy)
    private var statusJob: Job? = null

    /**
     * Начать наблюдение за туннелем и защитой.
     *
     * Вызывается экраном при появлении, а НЕ из конструктора: таймер
     * перепроверки делает сетевые обращения, и запускать его до того, как экран
     * показан, значит опрашивать сеть в фоне без причины.
     */
    fun start() {
        if (statusJob?.isActive == true) return

        statusJob = viewModelScope.launch {
            tunnel.status.collect { incoming ->
                // Новая сеть: всё, что было измерено раньше, к ней не относится.
                generation++
                measured.value = null
                measuredGeneration = -1
                system.value = incoming
                recompute()
            }
        }

        watcher.start(
            scope = viewModelScope,
            networkChanges = tunnel.networkChanges,
            onVerdict = { recordMeasurement(it) },
            onInvalidated = { invalidateMeasurement() },
        )
    }

    /** Остановить наблюдение: экран ушёл, опрашивать сеть незачем. */
    fun stop() {
        statusJob?.cancel()
        statusJob = null
        watcher.stop()
    }

    /**
     * Подключиться и подтвердить защиту.
     *
     * `yield` даёт коллектору обработать эмит туннеля раньше, чем будет записан
     * вердикт, — иначе порядок записей не определён.
     */
    suspend fun connect() {
        tunnel.connect()
        yield()
        recordMeasurement(gate.evaluate())
    }

    suspend fun disconnect() {
        tunnel.disconnect()
    }

    /** Перепроверка по требованию, например с кнопки «проверить снова». */
    suspend fun reverify() {
        recordMeasurement(gate.evaluate())
        tunnel.reverifyProtection()
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }

    /** Записать свежий вердикт: он относится к текущему поколению сети. */
    private fun recordMeasurement(verdict: ConnectionStatus) {
        measured.value = verdict
        measuredGeneration = generation
        recompute()
    }

    /** Смена сети: прежний замер больше не описывает действительность. */
    private fun invalidateMeasurement() {
        measured.value = null
        measuredGeneration = -1
        recompute()
    }

    private fun recompute() {
        _status.value = resolve(system.value, measured.value, measuredGeneration == generation)
    }

    /**
     * Что показать.
     *
     * Порядок правил:
     *  1. туннеля нет или он сломался — показываем это, каким бы ни был замер:
     *     старое зелёное не должно пережить отключение;
     *  2. замер, снятый на текущем поколении сети, — он и решает (и именно он
     *     может дать зелёное);
     *  3. иначе — состояние туннеля; зелёным оно быть не может по типу.
     *
     * Замер, снятый на прошлом поколении, сюда не доходит: свежий зелёный,
     * полученный уже ПОСЛЕ эмита туннеля, обязан показываться, иначе индикатор
     * залипал бы в «не проверено» при исправной сети.
     */
    private fun resolve(
        system: ConnectionStatus,
        measured: ConnectionStatus?,
        measuredIsFresh: Boolean,
    ): ConnectionStatus = when {
        system is ConnectionStatus.Disconnected || system is ConnectionStatus.Failed -> system
        measured != null && measuredIsFresh -> measured
        else -> system
    }
}
