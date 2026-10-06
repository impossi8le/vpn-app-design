package com.impossi8le.vpnapp.domain.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Разбор адресов обхода. Формат — CIDR (как отдаёт сервер). Раньше был ещё
 * формат «route … маска … net_gateway», но у него не осталось ни одного
 * боевого потребителя (сервис читает CIDR), и держать два парсера там, где
 * работает один, значило бы приглашать будущую путаницу. Он удалён вместе с
 * тестами.
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
    fun `слишком широкая подсеть обходом не становится — это была бы утечка всего`() {
        // `/0` и `/7` означали бы «пусти весь интернет мимо туннеля» — ровно
        // противоположное смыслу обхода и полная молчаливая утечка. Такие
        // записи обязаны отбрасываться, а не превращаться в маршрут.
        assertNull(parseBypassCidr("0.0.0.0/0"))
        assertNull(parseBypassCidr("10.0.0.0/7"))
        // Граница сохранена: /8 — уже настоящая подсеть, её терять нельзя.
        assertEquals(BypassRoute("10.0.0.0", 8), parseBypassCidr("10.0.0.0/8"))
    }

    @Test
    fun `маска переводится в длину префикса`() {
        assertEquals(24, maskToPrefixLength("255.255.255.0"))
        assertEquals(16, maskToPrefixLength("255.255.0.0"))
        assertEquals(32, maskToPrefixLength("255.255.255.255"))
        // `0.0.0.0` — маска в ноль бит, то есть «весь интернет мимо туннеля».
        // Как и `/0` в CIDR, это не обход, а утечка: возвращаем null.
        assertNull(maskToPrefixLength("0.0.0.0"))
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
}
