package com.impossi8le.vpnapp.domain.protection

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Фейк принимает ФАКТЫ и честно прогоняет их через evaluate.
 *
 * Такой фейк не может «подсунуть» ответ: чтобы тест прошёл, вердикт должен быть
 * вычислен самой реализацией. Фейк вида `object : ProtectionProbe { return
 * Confirmed(...) }` сделал бы тест тавтологичным — он проверял бы, что заглушка
 * возвращает то, что в неё вписали.
 */
private class FactProbe(private val facts: Triple<Boolean, Boolean, Boolean>) : ProtectionProbe {
    override suspend fun verify(): ProtectionVerdict =
        ProtectionVerdict.evaluate(facts.first, facts.second, facts.third)
}

private class ThrowingProbe : ProtectionProbe {
    override suspend fun verify(): ProtectionVerdict =
        throw IllegalStateException("нет активной сети")
}

class ProtectionGateTest {

    @Test
    fun `три истинных факта дают зелёное`() = runTest {
        val status = ProtectionGate(FactProbe(Triple(true, true, true))).evaluate()
        assertTrue(status is ConnectionStatus.Protected, "ожидалось Protected, получено $status")
    }

    @Test
    fun `любой ложный факт даёт ProtectionFailed, а не вечное «проверяем»`() = runTest {
        val status = ProtectionGate(FactProbe(Triple(true, false, true))).evaluate()
        assertTrue(
            status is ConnectionStatus.ProtectionFailed,
            "провал пробы обязан быть третьим исходом, получено $status",
        )
    }

    @Test
    fun `исключение в пробе даёт ProbeUnavailable, а не падение`() = runTest {
        val status = ProtectionGate(ThrowingProbe()).evaluate()
        assertTrue(status is ConnectionStatus.ProtectionFailed)
        val failure = (status as ConnectionStatus.ProtectionFailed).verdict.failure
        assertEquals(
            ProtectionFailure.ProbeUnavailable,
            failure,
            "сбой пробы — это не то же самое, что отрицательный замер",
        )
    }

    @Test
    fun `gate не возвращает зелёное повторно из кэша после провала`() = runTest {
        // Первый замер успешен, второй падает: старый зелёный НЕ должен пережить
        // неудачный повторный замер. Иначе однократное подтверждение остаётся
        // зелёным навсегда, даже когда маршруты уехали.
        var facts = Triple(true, true, true)
        val gate = ProtectionGate { ProtectionVerdict.evaluate(facts.first, facts.second, facts.third) }

        assertTrue(gate.evaluate().isProtected)
        facts = Triple(true, false, true)
        assertTrue(
            gate.evaluate() is ConnectionStatus.ProtectionFailed,
            "после провалившегося повторного замера зелёное недопустимо",
        )
    }
}
