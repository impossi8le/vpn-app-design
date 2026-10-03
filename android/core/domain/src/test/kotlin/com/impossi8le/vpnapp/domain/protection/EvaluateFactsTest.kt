package com.impossi8le.vpnapp.domain.protection

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Вся логика §6, доступная на JVM.
 *
 * Платформенный код только заполняет [LinkFacts]; решение принимается здесь,
 * поэтому здесь и проверяются все комбинации — включая те, что означают утечку.
 */
class EvaluateFactsTest {

    private fun facts(
        routes: List<String> = listOf("0.0.0.0/0", "::/0"),
        dns: List<String> = listOf("10.8.0.1"),
    ) = LinkFacts(routes = routes, dnsServers = dns)

    @Test
    fun `правильно настроенный линк даёт зелёное`() {
        assertTrue(evaluateFacts(facts(), tunnelDns = listOf("10.8.0.1")).isConfirmed)
    }

    @Test
    fun `отсутствие маршрута по умолчанию не даёт зелёного`() {
        val verdict = evaluateFacts(
            facts(routes = listOf("::/0")),
            tunnelDns = listOf("10.8.0.1"),
        )
        assertFalse(verdict.isConfirmed, "IPv4 не перехвачен — трафик идёт мимо туннеля")
    }

    @Test
    fun `ИМЕННО отсутствие ::/0 не даёт зелёного`() {
        // Ключевой случай: IPv6 не закрыт. Система продолжит выпускать
        // IPv6-трафик в обход туннеля, а IPv4 при этом выглядит правильно.
        val verdict = evaluateFacts(
            facts(routes = listOf("0.0.0.0/0")),
            tunnelDns = listOf("10.8.0.1"),
        )
        assertFalse(verdict.isConfirmed, "незакрытый IPv6 — это утечка, а не деталь")
    }

    @Test
    fun `системный DNS вместо туннельного не даёт зелёного`() {
        val verdict = evaluateFacts(
            facts(dns = listOf("8.8.8.8")),
            tunnelDns = listOf("10.8.0.1"),
        )
        assertFalse(verdict.isConfirmed, "DNS вне туннеля означает утечку запросов")
    }

    @Test
    fun `частичное совпадение DNS не считается закрытым`() {
        // Один из двух резолверов профиля уехал наружу — половина запросов
        // уходит в обход, и это всё равно утечка.
        val verdict = evaluateFacts(
            facts(dns = listOf("10.8.0.1", "8.8.8.8")),
            tunnelDns = listOf("10.8.0.1", "10.8.0.2"),
        )
        assertFalse(verdict.isConfirmed)
    }

    @Test
    fun `несколько резолверов профиля все внутри — зелёное`() {
        val verdict = evaluateFacts(
            facts(dns = listOf("10.8.0.1", "10.8.0.2")),
            tunnelDns = listOf("10.8.0.1", "10.8.0.2"),
        )
        assertTrue(verdict.isConfirmed)
    }

    @Test
    fun `пустой список маршрутов не даёт зелёного`() {
        assertFalse(evaluateFacts(facts(routes = emptyList()), tunnelDns = listOf("10.8.0.1")).isConfirmed)
    }

    @Test
    fun `пустой DNS профиля не даёт зелёного`() {
        // Нечего сверять — значит подтвердить нечего.
        assertFalse(evaluateFacts(facts(), tunnelDns = emptyList()).isConfirmed)
    }

    @Test
    fun `пробелы в фактах не ломают сверку`() {
        val verdict = evaluateFacts(
            LinkFacts(routes = listOf(" 0.0.0.0/0 ", " ::/0 "), dnsServers = listOf(" 10.8.0.1 ")),
            tunnelDns = listOf("10.8.0.1"),
        )
        assertTrue(verdict.isConfirmed)
    }

    @Test
    fun `лишние маршруты не мешают`() {
        // Реальный туннель почти всегда имеет несколько маршрутов; наличие
        // дополнительных не означает проблему.
        val verdict = evaluateFacts(
            facts(routes = listOf("10.8.0.0/24", "0.0.0.0/0", "::/0", "fd00::/64")),
            tunnelDns = listOf("10.8.0.1"),
        )
        assertTrue(verdict.isConfirmed)
    }
}
