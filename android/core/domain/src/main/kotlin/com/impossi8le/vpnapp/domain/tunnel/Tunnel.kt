package com.impossi8le.vpnapp.domain.tunnel

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
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

    /** Запустить туннель. Идемпотентно: повторный вызов не поднимает второй. */
    suspend fun connect()

    suspend fun disconnect()

    /** Перепроверить защиту, не трогая туннель. Нужна при смене сети. */
    suspend fun reverifyProtection()
}
