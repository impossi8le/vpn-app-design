package com.impossi8le.vpnapp.domain.protection

/**
 * Доказательство защиты. Конструктор `internal` — собрать его можно только
 * внутри core:domain. Внешний модуль не может выдать «зелёное» руками:
 * у него нет способа создать evidence, а значит нет способа создать
 * ConnectionStatus.Protected.
 */
class ProtectionEvidence internal constructor(
    val ipv4InTunnel: Boolean,
    val ipv6Closed: Boolean,
    val dnsInside: Boolean,
)

/** Почему защита не подтверждена. Разные причины — разный текст пользователю. */
sealed interface ProtectionFailure {
    /** Проба не смогла ответить: нет активной сети или платформенный код бросил. */
    data object ProbeUnavailable : ProtectionFailure

    /** Проба ответила, но хотя бы один признак ложен. */
    data object Inconclusive : ProtectionFailure
}

sealed interface ProtectionVerdict {
    data class Confirmed(val evidence: ProtectionEvidence) : ProtectionVerdict
    data class Failed(val failure: ProtectionFailure) : ProtectionVerdict

    companion object {
        /**
         * ЕДИНСТВЕННЫЙ конструктор зелёного. Все три условия обязаны быть истинны.
         *
         * Проверка стоит на входе, а не на выводе: любой вызывающий, передавший
         * `false` хотя бы в одном признаке, физически не может получить Confirmed.
         * Это и есть инвариант §6 — не договорённость между модулями, а свойство типа.
         */
        fun evaluate(
            ipv4InTunnel: Boolean,
            ipv6Closed: Boolean,
            dnsInside: Boolean,
        ): ProtectionVerdict =
            if (ipv4InTunnel && ipv6Closed && dnsInside) {
                Confirmed(ProtectionEvidence(true, true, true))
            } else {
                Failed(ProtectionFailure.Inconclusive)
            }
    }
}

val ProtectionVerdict.isConfirmed: Boolean
    get() = this is ProtectionVerdict.Confirmed
