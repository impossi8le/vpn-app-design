package com.impossi8le.vpnapp

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.tunnel.TunnelControlling
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Заглушка туннеля: движок не подключён.
 *
 * Существует, чтобы приложение собиралось и запускалось целиком, пока не решён
 * вопрос с ядром OpenVPN (§4.7 архитектуры: ics-openvpn — приложение, а не
 * библиотека, способ подключения не выбран).
 *
 * **Честность важнее удобства:** `connect()` переводит экран в состояние отказа
 * с внятной причиной, а НЕ в «подключено». Заглушка, изображающая успех,
 * породила бы ровно ту ложную уверенность, против которой написан весь инвариант
 * защиты: пользователь увидел бы интерфейс VPN, за которым ничего нет.
 */
class TunnelEnginePending : TunnelControlling {

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val status: StateFlow<ConnectionStatus> = _status

    // Смены сети подписчику неоткуда взять: туннеля нет.
    override val networkChanges: Flow<Unit> = emptyFlow()

    override suspend fun connect() {
        _status.value = ConnectionStatus.Failed(
            "Движок VPN ещё не подключён: способ встраивания ядра OpenVPN не выбран",
        )
    }

    override suspend fun disconnect() {
        _status.value = ConnectionStatus.Disconnected
    }

    override suspend fun reverifyProtection() {
        // Проверять нечего: туннель не поднимался.
    }
}
