package com.impossi8le.vpnapp.domain.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Правила настройки туннеля как данные.
 *
 * Ошибка в этих правилах означает не «неудобно», а «трафик уходит мимо
 * туннеля», поэтому они проверяются здесь, на JVM, — в отличие от самого
 * вызова `Builder`, который без устройства не проверить.
 */
class TunnelPlanTest {

    private fun addressing(
        dns: List<String> = listOf("10.8.0.1"),
        mtu: Int = 1400,
    ) = TunnelAddressing(
        ipv4Address = "10.8.0.2",
        ipv4PrefixLength = 24,
        dnsServers = dns,
        mtu = mtu,
    )

    @Test
    fun `IPv6 закрывается явным маршрутом, а не отсутствием настройки`() {
        val plan = TunnelPlan.build(addressing())

        assertTrue(plan.ipv6Closed, "без маршрута ::/0 весь IPv6 уходит в обход")
        assertTrue(plan.routes.contains("::/0"))
    }

    @Test
    fun `весь IPv4 направляется в туннель`() {
        val plan = TunnelPlan.build(addressing())
        assertTrue(plan.ipv4Captured)
        assertTrue(plan.routes.contains("0.0.0.0/0"))
    }

    @Test
    fun `DNS берётся из профиля, а не системный`() {
        val plan = TunnelPlan.build(addressing(dns = listOf("10.8.0.1", "10.8.0.2")))
        assertEquals(listOf("10.8.0.1", "10.8.0.2"), plan.dnsServers)
    }

    @Test
    fun `план без DNS не собирается`() {
        // Системный резолвер означал бы утечку запросов в обход туннеля.
        assertThrows(IllegalArgumentException::class.java) {
            TunnelPlan.build(addressing(dns = emptyList()))
        }
    }

    @Test
    fun `план с blocking=false не собирается`() {
        // Иначе при обрыве остаётся окно, в которое трафик уходит открытым.
        assertThrows(IllegalArgumentException::class.java) {
            TunnelPlan.build(addressing(), blocking = false)
        }
    }

    @Test
    fun `MTU вне разумного диапазона отвергается`() {
        assertThrows(IllegalArgumentException::class.java) {
            TunnelPlan.build(addressing(mtu = 9000))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TunnelPlan.build(addressing(mtu = 100))
        }
    }

    @Test
    fun `ни одно приложение не исключается из туннеля`() {
        val plan = TunnelPlan.build(addressing())

        assertTrue(
            plan.disallowedApplications.isEmpty(),
            "исключённое приложение обходит туннель, и в таблице маршрутов это не видно — проба такого не поймает",
        )
    }

    @Test
    fun `адрес туннеля собирается с маской`() {
        val plan = TunnelPlan.build(addressing())
        assertEquals(listOf("10.8.0.2/24"), plan.addresses)
    }

    @Test
    fun `blocking включён по умолчанию`() {
        assertTrue(TunnelPlan.build(addressing()).blocking)
    }

    @Test
    fun `правильный MTU принимается`() {
        assertFalse(TunnelPlan.build(addressing(mtu = 1300)).mtu == 1400)
        assertEquals(1300, TunnelPlan.build(addressing(mtu = 1300)).mtu)
    }
}
