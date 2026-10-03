package com.impossi8le.vpnapp.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionGate
import com.impossi8le.vpnapp.domain.tunnel.TunnelControlling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
 * должно его пережить. Это не оптимизация, а требование §6.
 *
 * Зависимость на [ProtectionGate], а не на пробу, не даёт этому классу собрать
 * `Protected` руками: `ProtectionEvidence` вне `core:domain` не конструируется.
 */
class HomeViewModel(
    private val tunnel: TunnelControlling,
    private val gate: ProtectionGate,
) : ViewModel() {

    private val system = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)

    /** Результат последнего замера. `null` — замера для текущего состояния нет. */
    private val measured = MutableStateFlow<ConnectionStatus?>(null)

    val status: StateFlow<ConnectionStatus> =
        combine(system, measured) { sys, verdict -> resolve(sys, verdict) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionStatus.Disconnected)

    init {
        viewModelScope.launch {
            tunnel.status.collect { incoming ->
                // Замер обесценивается ДО применения состояния: он относился к
                // прежней сети, а не к этой.
                measured.value = null
                system.value = incoming
            }
        }
        viewModelScope.launch {
            // ОТДЕЛЬНЫЙ канал: смена сети может не изменить `status` вовсе,
            // а StateFlow одинаковые значения схлопывает. Без этой подписки
            // зелёное пережило бы роуминг, если статус остался «поднято».
            tunnel.networkChanges.collect { measured.value = null }
        }
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
        measured.value = gate.evaluate()
    }

    suspend fun disconnect() {
        tunnel.disconnect()
    }

    /** Перепроверка при смене сети: зелёное не сохраняется, а подтверждается заново. */
    suspend fun reverify() {
        measured.value = gate.evaluate()
        tunnel.reverifyProtection()
    }

    /**
     * Системное состояние имеет приоритет, когда оно означает «туннеля нет»:
     * старое зелёное не должно пережить отключение или сбой ни при каких
     * обстоятельствах, даже если замер почему-то не обнулился.
     */
    private fun resolve(system: ConnectionStatus, measured: ConnectionStatus?): ConnectionStatus =
        when {
            system is ConnectionStatus.Disconnected || system is ConnectionStatus.Failed -> system
            measured != null -> measured
            else -> system
        }
}
