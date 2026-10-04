package com.impossi8le.vpnapp

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionFailure
import com.impossi8le.vpnapp.domain.protection.ProtectionGate
import com.impossi8le.vpnapp.domain.protection.ProtectionProbe
import com.impossi8le.vpnapp.domain.protection.ProtectionVerdict
import com.impossi8le.vpnapp.domain.tunnel.TunnelControlling
import com.impossi8le.vpnapp.network.MockApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Туннель-заглушка, честно сообщающий об отказе.
 *
 * Боевой путь теперь идёт через `VpnTunnelService` и `AppTunnelController`
 * (см. composition root). Этот класс оставлен для тестов и для экранов, где
 * реальный сервис недоступен: `connect()` переводит экран в состояние
 * **«движок не запущен»**, а не в «подключено».
 *
 * Соблазн сделать заглушку «успешно подключено» здесь особенно велик — и
 * особенно опасен: пользователь увидел бы интерфейс VPN, за которым ничего нет,
 * ровно ту ложную уверенность, против которой написан весь инвариант §6.
 */
class DemoTunnelEngine : TunnelControlling {

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    override val status: StateFlow<ConnectionStatus> = _status

    // Смены сети сообщать неоткуда: реального туннеля нет.
    private val _networkChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    override val networkChanges: Flow<Unit> = _networkChanges.asSharedFlow()

    override suspend fun connect() {
        _status.value = ConnectionStatus.Failed(
            "Туннель не запущен: это заглушка, реальный путь — VpnTunnelService",
        )
    }

    override suspend fun disconnect() {
        _status.value = ConnectionStatus.Disconnected
    }

    override suspend fun reverifyProtection() {
        // Проверять нечего: туннель не поднимался.
    }
}

/**
 * Демо-режим: подставной бэкенд вместо живого сервера.
 *
 * Пользователь просил захардкоженные вход и регистрацию, чтобы пройти сценарий
 * без бэкенда. Здесь собрано ровно это: [MockApi] отвечает вместо сети.
 *
 * **Граница, которую нельзя сдвигать.** Подставной API имитирует ТОЛЬКО сеть и
 * авторизацию. Он не имеет доступа к `ProtectionVerdict` — и не может его
 * произвести: доказательство защиты собирается единственным конструктором в
 * `core:domain`, а тот требует трёх измеренных фактов. Пока туннель не поднят,
 * экран честно показывает «защита не проверена», и никакая подстановка этого не
 * меняет. Тест `MockApiTest` проверяет это свойство явно.
 */
object DemoMode {
    val api: MockApi by lazy { MockApi() }

    val isEnabled: Boolean = true
}

/**
 * Проба, которая честно сообщает, что проверить не может.
 *
 * Возвращает `ProbeUnavailable`, а не зелёное: подтверждать нечего, пока нет
 * туннеля. Это не «зелёное по умолчанию» — это признание неизвестности, и
 * `ProtectionGate` превратит его в `ProtectionFailed`, а не в «защищено».
 */
object UnavailableProbe : ProtectionProbe {
    override suspend fun verify(): ProtectionVerdict =
        ProtectionVerdict.Failed(ProtectionFailure.ProbeUnavailable)
}
