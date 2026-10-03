package com.impossi8le.vpnapp.tunnel

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionEvidence

/**
 * Системное состояние VpnService, как его отдаёт платформа.
 *
 * В домен этот тип не протекает: он живёт в core:tunnel и переводится в
 * ConnectionStatus явным маппингом ниже.
 */
enum class SystemState { IDLE, CONNECTING, ESTABLISHED, LOST, FAILED }

/**
 * Маппинг системного состояния в доменное.
 *
 * ESTABLISHED даёт VerifyingProtection, а не Protected, и это не деталь UI —
 * это инвариант: поднятый интерфейс не доказывает, что трафик идёт через него.
 * Функция физически не способна вернуть Protected: у неё нет ProtectionEvidence,
 * а конструктор evidence — `internal` в core:domain. Чтобы скомпилировать
 * Protected здесь, пришлось бы сначала ослабить саму защиту в домене.
 */
fun SystemState.toConnectionStatus(): ConnectionStatus = when (this) {
    SystemState.IDLE -> ConnectionStatus.Disconnected
    SystemState.CONNECTING -> ConnectionStatus.Connecting
    SystemState.ESTABLISHED -> ConnectionStatus.VerifyingProtection
    SystemState.LOST -> ConnectionStatus.Disconnected
    SystemState.FAILED -> ConnectionStatus.Failed("туннель не поднялся")
}
