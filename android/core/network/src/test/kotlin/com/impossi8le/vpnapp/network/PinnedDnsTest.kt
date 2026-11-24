package com.impossi8le.vpnapp.network

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Резолв имени нашего сервера не должен спрашивать DNS.
 *
 * Проверено на устройстве (issue #34): под VPN система ставит первым DNS-сервером
 * `10.91.0.1` — адрес туннеля, который не отвечает. Android спрашивает серверы по
 * порядку, запрос к нашему API ждал недоступный первый **48 секунд** и падал,
 * тогда как обращение по IP отвечало за 0.1 с. Практически это ломало добавление
 * своего обхода: «Не удалось проверить адрес».
 */
class PinnedDnsTest {

    /** Резолвер, который падает: если его позвали, значит подмена не сработала. */
    private val forbidden = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            throw UnknownHostException("DNS спрашивать нельзя: $hostname")
    }

    @Test
    fun `имя нашего сервера резолвится без запроса к DNS`() {
        val dns = PinnedDns("https://193-233-245-95.sslip.io/api/v1", forbidden)

        val addresses = dns.lookup("193-233-245-95.sslip.io")

        assertEquals(1, addresses.size)
        assertEquals("193.233.245.95", addresses.first().hostAddress)
    }

    @Test
    fun `регистр имени не мешает подмене`() {
        // DNS регистронезависим: имя в baseUrl и в запросе может отличаться
        // регистром, и подмена обязана сработать всё равно.
        val dns = PinnedDns("https://193-233-245-95.SSLIP.IO/api/v1", forbidden)

        assertEquals(
            "193.233.245.95",
            dns.lookup("193-233-245-95.sslip.io").first().hostAddress,
        )
    }

    @Test
    fun `чужое имя идёт обычным резолвом`() {
        // Приватность: подменяем ровно одно имя — адрес нашего сервера. Всё
        // остальное должно спрашиваться как раньше.
        var asked: String? = null
        val delegate = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                asked = hostname
                return listOf(InetAddress.getByName("127.0.0.1"))
            }
        }
        val dns = PinnedDns("https://193-233-245-95.sslip.io/api/v1", delegate)

        val result = dns.lookup("example.com")

        assertEquals("example.com", asked, "чужие имена обязаны уходить обычному резолверу")
        assertEquals("127.0.0.1", result.first().hostAddress)
    }

    @Test
    fun `обычный домен в baseUrl не подменяет ничего`() {
        // Если адрес в имени не закодирован, разобрать его без сети нельзя.
        // Подменять что-либо значило бы угадывать — такой запрос идёт обычным
        // путём, как и раньше.
        val dns = PinnedDns("https://vpnbotfree.ru/api/v1", forbidden)

        assertThrows<UnknownHostException> { dns.lookup("vpnbotfree.ru") }
    }

    @Test
    fun `порт и путь в базовом адресе не мешают разбору`() {
        val cases = listOf(
            "https://193-233-245-95.sslip.io/api/v1",
            "https://193-233-245-95.sslip.io/api/v1",
            "http://193-233-245-95.sslip.io:8080",
            "https://193-233-245-95.sslip.io",
        )

        cases.forEach { url ->
            val dns = PinnedDns(url, forbidden)
            assertEquals(
                "193.233.245.95",
                dns.lookup("193-233-245-95.sslip.io").first().hostAddress,
                "не разобрался адрес: $url",
            )
        }
    }

    @Test
    fun `адрес берётся из имени, а не угадывается`() {
        // Другой закодированный адрес должен дать именно себя.
        val dns = PinnedDns("https://10-20-30-40.sslip.io/api/v1", forbidden)

        assertEquals("10.20.30.40", dns.lookup("10-20-30-40.sslip.io").first().hostAddress)
    }
}
