package com.impossi8le.vpnapp.testsupport

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.tunnel.TunnelControlling
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Туннель-заглушка.
 *
 * Намеренно НЕ умеет выставить `.Protected`: интерфейс TunnelControlling этого
 * не позволяет, а собрать ProtectionEvidence вне core:domain нельзя. То есть
 * даже захотев, тест не сможет «подсунуть» зелёное в обход пробы — это и есть
 * проверяемое свойство.
 */
class FakeTunnelControlling(
    initial: ConnectionStatus = ConnectionStatus.Disconnected,
) : TunnelControlling {

    private val _status = MutableStateFlow(initial)
    override val status: StateFlow<ConnectionStatus> = _status

    // SharedFlow, а не StateFlow: смену сети нельзя схлопывать — повторный сигнал
    // при неизменившемся статусе должен дойти до подписчика.
    private val _networkChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val networkChanges: Flow<Unit> = _networkChanges.asSharedFlow()

    var connectCount: Int = 0
        private set
    var disconnectCount: Int = 0
        private set
    var reverifyCount: Int = 0
        private set

    /** После connect() туннель «поднимается» — но только до «проверяем защиту». */
    var statusAfterConnect: ConnectionStatus = ConnectionStatus.VerifyingProtection

    override suspend fun connect() {
        connectCount++
        _status.value = statusAfterConnect
    }

    override suspend fun disconnect() {
        disconnectCount++
        _status.value = ConnectionStatus.Disconnected
    }

    override suspend fun reverifyProtection() {
        reverifyCount++
    }

    fun emit(status: ConnectionStatus) {
        _status.value = status
    }

    /**
     * Смена сети. Сигнал идёт отдельным каналом: статус при этом может остаться
     * прежним, и через `status` (StateFlow) такое событие не донести.
     */
    fun emitNetworkChange() {
        _networkChanges.tryEmit(Unit)
    }
}
