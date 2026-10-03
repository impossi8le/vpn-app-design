package com.impossi8le.vpnapp.domain.protection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Инвариант §6 в чистом виде: зелёное существует ровно при трёх истинах.
 * Проверяются ВСЕ восемь комбинаций — не выборка, потому что «забытый» случай
 * здесь означает ложную защиту.
 */
class ProtectionVerdictTest {

    @ParameterizedTest(name = "ipv4={0} ipv6={1} dns={2} → зелёное={3}")
    @CsvSource(
        "true,  true,  true,  true",
        "false, true,  true,  false",
        "true,  false, true,  false",
        "true,  true,  false, false",
        "false, false, true,  false",
        "false, true,  false, false",
        "true,  false, false, false",
        "false, false, false, false",
    )
    fun `зелёное только при всех трёх истинах`(
        ipv4InTunnel: Boolean,
        ipv6Closed: Boolean,
        dnsInside: Boolean,
        expectedConfirmed: Boolean,
    ) {
        val verdict = ProtectionVerdict.evaluate(ipv4InTunnel, ipv6Closed, dnsInside)
        assertEquals(expectedConfirmed, verdict.isConfirmed)
    }

    @Test
    fun `подтверждённый вердикт несёт evidence со всеми тремя истинами`() {
        val evidence = (ProtectionVerdict.evaluate(true, true, true) as ProtectionVerdict.Confirmed).evidence
        assertTrue(evidence.ipv4InTunnel)
        assertTrue(evidence.ipv6Closed)
        assertTrue(evidence.dnsInside)
    }

    @Test
    fun `провал помечается Inconclusive, а не ProbeUnavailable`() {
        val verdict = ProtectionVerdict.evaluate(false, false, false)
        assertTrue(verdict is ProtectionVerdict.Failed)
        assertEquals(ProtectionFailure.Inconclusive, (verdict as ProtectionVerdict.Failed).failure)
    }

    @Test
    fun `единственный ложный признак уже не даёт зелёного`() {
        // Отдельно от параметризованного теста: здесь важен не факт провала,
        // а то, что одного признака достаточно. Это защита от «почти закрыто».
        assertFalse(ProtectionVerdict.evaluate(true, true, false).isConfirmed)
    }
}
