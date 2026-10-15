package com.impossi8le.vpnapp.domain.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Извлечение адреса сервера из текста профиля.
 *
 * Адрес нужен диагностике: в отчёт и в метрики он входит, а «Сервер: неизвестно»
 * было честной, но бесполезной строкой. Читать `.ovpn` ради неё из UI нельзя —
 * там приватный ключ, и открывать его лишний раз незачем. Поэтому адрес достаётся
 * ОДИН раз, при сохранении профиля, и кладётся рядом с метаданными.
 *
 * Проверяется на JVM: разбор текстовый и в ядре не нуждается.
 */
class ProfileServerTest {

    @Test
    fun `remote с тремя токенами даёт хост и порт`() {
        val profile = "client\ndev tun\nremote 192.0.2.10 443 udp\n"

        assertEquals("192.0.2.10:443", ProfileServer.of(profile))
    }

    @Test
    fun `протокол в адрес не попадает`() {
        val profile = "remote 192.0.2.10 1194 udp\n"

        assertEquals("192.0.2.10:1194", ProfileServer.of(profile))
    }

    @Test
    fun `перевод строки CRLF не ломает разбор`() {
        val profile = "client\r\nremote 192.0.2.10 443 udp\r\n"

        assertEquals("192.0.2.10:443", ProfileServer.of(profile))
    }

    @Test
    fun `берётся первый remote при нескольких`() {
        val profile = "remote 192.0.2.10 443 udp\nremote 198.51.100.7 1194 tcp\n"

        assertEquals("192.0.2.10:443", ProfileServer.of(profile), "первый — тот, что ядро берёт без remote-random")
    }

    @Test
    fun `комментарий и лишние пробелы не мешают`() {
        val profile = "  remote   192.0.2.10   443   tcp  # основной сервер\n"

        assertEquals("192.0.2.10:443", ProfileServer.of(profile))
    }

    @Test
    fun `закомментированный remote не берётся`() {
        val profile = "# remote 192.0.2.10 443\nremote 198.51.100.7 1194\n"

        assertEquals("198.51.100.7:1194", ProfileServer.of(profile))
    }

    @Test
    fun `hostname тоже годится`() {
        val profile = "remote vpn.example.com 1194 udp\n"

        assertEquals("vpn.example.com:1194", ProfileServer.of(profile))
    }

    @Test
    fun `remote без порта даёт только хост`() {
        val profile = "remote vpn.example.com\n"

        assertEquals("vpn.example.com", ProfileServer.of(profile))
    }

    @Test
    fun `без remote адреса нет, а не пустая строка`() {
        assertNull(ProfileServer.of("client\ndev tun\n"), "адреса нет — честный null, а не «»")
    }

    @Test
    fun `похожая директива не считается remote`() {
        val profile = "remote-random\nremote-cert-tls server\n"

        assertNull(ProfileServer.of(profile), "`remote-random` — не адрес: иначе в отчёт ушёл бы мусор")
    }
}
