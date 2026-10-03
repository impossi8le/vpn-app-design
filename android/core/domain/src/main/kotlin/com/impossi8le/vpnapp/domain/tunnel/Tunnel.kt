package com.impossi8le.vpnapp.domain.tunnel

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Управление туннелем со стороны приложения.
 *
 * Интерфейс намеренно НЕ умеет сообщать о защите: `status` отдаёт состояния
 * туннеля, а зелёное появляется в потоке только после того, как `ProtectionGate`
 * проведёт замер. Соблазн «раз туннель поднят, покажем зелёное» разбивается о
 * эту границу: у реализации просто нет evidence, чтобы собрать `.Protected`.
 */
interface TunnelControlling {
    val status: StateFlow<ConnectionStatus>

    /**
     * Смена сети: роуминг, переход Wi-Fi ↔ LTE, потеря и восстановление линка.
     *
     * Отдельный сигнал, потому что [status] — `StateFlow`, а он схлопывает
     * одинаковые значения. «Тот же статус, другая сеть» через него не выразить:
     * повторная эмиссия `VerifyingProtection` не придёт подписчику вовсе. А для
     * §6 это принципиально — замер, сделанный в прежней сети, после смены
     * недействителен, и зелёное обязано исчезнуть даже если статус не изменился.
     */
    val networkChanges: Flow<Unit>

    /** Запустить туннель. Идемпотентно: повторный вызов не поднимает второй. */
    suspend fun connect()

    suspend fun disconnect()

    /** Перепроверить защиту, не трогая туннель. Нужна при смене сети. */
    suspend fun reverifyProtection()
}
