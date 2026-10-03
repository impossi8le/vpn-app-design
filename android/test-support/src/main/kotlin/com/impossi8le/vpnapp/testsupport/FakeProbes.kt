package com.impossi8le.vpnapp.testsupport

import com.impossi8le.vpnapp.domain.protection.ProtectionProbe
import com.impossi8le.vpnapp.domain.protection.ProtectionVerdict

/**
 * Проба, которая принимает ФАКТЫ и честно прогоняет их через `evaluate`.
 *
 * Такой фейк не может подсунуть ответ: чтобы тест прошёл, вердикт обязан быть
 * вычислен реализацией. Проба вида «всегда возвращаю `Confirmed`» сделала бы
 * тест тавтологичным — он проверял бы, что заглушка возвращает то, что в неё
 * вписали, и не поймал бы ни одной реальной ошибки в `ProtectionGate`.
 */
class FactProbe(private var facts: Triple<Boolean, Boolean, Boolean>) : ProtectionProbe {
    override suspend fun verify(): ProtectionVerdict =
        ProtectionVerdict.evaluate(facts.first, facts.second, facts.third)

    /** Сменить факты между замерами: сеть поменялась, маршруты уехали. */
    fun withFacts(ipv4: Boolean, ipv6: Boolean, dns: Boolean) {
        facts = Triple(ipv4, ipv6, dns)
    }
}

/** Проба, которая падает: нет активной сети или платформенный код бросил. */
class ThrowingProbe(private val error: Exception = IllegalStateException("нет сети")) : ProtectionProbe {
    override suspend fun verify(): ProtectionVerdict = throw error
}
