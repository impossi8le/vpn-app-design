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
import kotlinx.coroutines.yield

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
     * Замер идёт ПОСЛЕ ожидания, пока туннель сообщит о себе: иначе вердикт
     * гейта может быть затёрт отложенным «поднято» от коллектора — зелёное
     * мигнёт и пропадёт, а на медленном устройстве это выглядит как мерцание.
     *
     * Результат замера имеет приоритет: он и только он решает, зелёное ли.
     */
    suspend fun connect() {
        tunnel.connect()
        // Ждём, пока коллектор отработает эмит туннеля, и только потом оцениваем.
        yield()
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
