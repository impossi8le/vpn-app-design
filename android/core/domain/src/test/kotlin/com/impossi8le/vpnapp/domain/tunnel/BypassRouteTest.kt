package com.impossi8le.vpnapp.domain.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Разбор адресов обхода. Два формата: CIDR (как отдаёт сервер) и маска (как
 * привычно в конфигах). Оба — в один тип, чтобы ниже был один механизм.
 */
class BypassRouteTest {

    @Test
    fun `cidr разбирается в сеть и префикс`() {
        assertEquals(BypassRoute("87.240.129.0", 24), parseBypassCidr("87.240.129.0/24"))
        assertEquals(BypassRoute("155.212.204.0", 24), parseBypassCidr("155.212.204.0/24"))
    }

    @Test
    fun `мусор в cidr даёт null, а не выдуманную сеть`() {
        assertNull(parseBypassCidr(""))
        assertNull(parseBypassCidr("87.240.129.0"))
        assertNull(parseBypassCidr("87.240.129.0/"))
        assertNull(parseBypassCidr("87.240.129.0/abc"))
        assertNull(parseBypassCidr("87.240.129.0/33"))
        assertNull(parseBypassCidr("не-адрес/24"))
    }

    @Test
    fun `маска переводится в длину префикса`() {
        assertEquals(24, maskToPrefixLength("255.255.255.0"))
        assertEquals(16, maskToPrefixLength("255.255.0.0"))
        assertEquals(32, maskToPrefixLength("255.255.255.255"))
        assertEquals(0, maskToPrefixLength("0.0.0.0"))
    }

    @Test
    fun `неровная маска — null, а не длина с потолка`() {
        // 255.255.255.128 — допустимая маска (/25), проверим отдельно
        assertEquals(25, maskToPrefixLength("255.255.255.128"))
        // Неровная маска (единицы вперемешку с нулями) не бывает длиной префикса
        assertNull(maskToPrefixLength("255.0.255.0"))
        assertNull(maskToPrefixLength("255.255.255"))
        assertNull(maskToPrefixLength("не-маска"))
    }

    @Test
    fun `строка route в формате владельца разбирается`() {
        assertEquals(
            BypassRoute("155.212.204.0", 24),
            parseBypassRouteLine("route 155.212.204.0 255.255.255.0 net_gateway"),
        )
        assertEquals(
            BypassRoute("87.240.129.0", 24),
            parseBypassRouteLine("route 87.240.129.0 255.255.255.0 net_gateway"),
        )
    }

    @Test
    fun `чужие строки и комментарии не разбираются`() {
        assertNull(parseBypassRouteLine("# vk.com"))
        assertNull(parseBypassRouteLine(""))
        assertNull(parseBypassRouteLine("route 10.0.0.0 255.0.0.0"))
        assertNull(parseBypassRouteLine("redirect-gateway def1"))
    }
}
