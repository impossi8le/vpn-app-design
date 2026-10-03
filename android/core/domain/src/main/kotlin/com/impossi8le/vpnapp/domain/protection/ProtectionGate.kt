package com.impossi8le.vpnapp.domain.protection

import com.impossi8le.vpnapp.domain.model.ConnectionStatus

/**
 * Проба защиты. Реализация на Android читает маршруты и DNS активного линка;
 * в тестах подставляется фейком.
 *
 * `fun interface` — чтобы тест мог написать пробу лямбдой и не заводить класс.
 */
fun interface ProtectionProbe {
    suspend fun verify(): ProtectionVerdict
}

/**
 * Единственный владелец перехода в зелёное состояние.
 *
 * Никакой другой код не конструирует ConnectionStatus.Protected: он требует
 * ProtectionEvidence, конструктор которого `internal` для core:domain, а
 * произвести evidence умеет только ProtectionVerdict.evaluate с тремя истинами.
 */
class ProtectionGate(private val probe: ProtectionProbe) {

    suspend fun evaluate(): ConnectionStatus = try {
        when (val verdict = probe.verify()) {
            is ProtectionVerdict.Confirmed -> ConnectionStatus.Protected(verdict.evidence)
            is ProtectionVerdict.Failed -> ConnectionStatus.ProtectionFailed(verdict)
        }
    } catch (_: Exception) {
        // Проба не смогла ответить — это ПРОВАЛ защиты, а не «проверяем вечно»
        // и не повод оставить предыдущее зелёное. Молчание пробы не означает
        // безопасность.
        ConnectionStatus.ProtectionFailed(
            ProtectionVerdict.Failed(ProtectionFailure.ProbeUnavailable),
        )
    }
}
