package com.impossi8le.vpnapp.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionGate
import com.impossi8le.vpnapp.domain.tunnel.TunnelControlling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Состояние главного экрана.
 *
 * Единственный путь к зелёному состоянию — через [ProtectionGate]. Зависимость
 * на `ProtectionGate` (а не на пробу напрямую) не даёт этому классу собрать
 * `Protected` руками: он не может произвести `ProtectionEvidence`.
 *
 * Требуется ViewModel из androidx, поэтому модуль — Android-библиотека; юнит-тесты
 * всё равно идут на JVM, потому что вне Composable-ов ViewModel работает как
 * обычный класс.
 */
class HomeViewModel(
    private val tunnel: TunnelControlling,
    private val gate: ProtectionGate,
) : ViewModel() {

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    init {
        viewModelScope.launch {
            tunnel.status.collect { _status.value = it }
        }
    }

    /**
     * Подключиться и подтвердить защиту.
     *
     * Порядок важен: сначала туннель, затем замер. Даже если туннель сообщит
     * «поднят», в UI уйдёт результат замера, а не системное состояние туннеля.
     */
    suspend fun connect() {
        tunnel.connect()
        // Отдельный шаг, а не побочный эффект: замер — самостоятельное действие.
        _status.value = gate.evaluate()
    }

    suspend fun disconnect() {
        tunnel.disconnect()
    }

    /** Перепроверка при смене сети: зелёное не сохраняется, а подтверждается заново. */
    suspend fun reverify() {
        _status.value = gate.evaluate()
        tunnel.reverifyProtection()
    }
}
