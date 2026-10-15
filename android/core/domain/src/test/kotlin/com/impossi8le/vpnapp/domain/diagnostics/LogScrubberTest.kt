package com.impossi8le.vpnapp.domain.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Вырезание секретов из строки лога.
 *
 * Логи ядра и сетевого слоя уходят на MOS, поэтому ключевой материал обязан
 * исчезнуть ещё до того, как строка попадёт в буфер. Это тот же класс защиты,
 * что `ProfileSanitizer` для `verb`: приватный ключ в логе — утечка доступа,
 * а не косметический дефект (прецедент — CVE в OpenVPN Connect < 3.5.0).
 */
class LogScrubberTest {

    @Test
    fun `тело PEM вырезается`() {
        val line = "wrote key -----BEGIN PRIVATE KEY-----MIIEvQIBADANBgkq-----END PRIVATE KEY-----"
        val scrubbed = LogScrubber.scrub(line)

        assertFalse(scrubbed.contains("MIIEvQIBADANBgkq"), "тело ключа не должно остаться")
        assertTrue(scrubbed.contains("[REDACTED]"), "на месте секрета обязана быть метка")
    }

    @Test
    fun `многострочный PEM превращается в одну метку`() {
        val log = """
            dumping key:
            -----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBgkqhkiG9w0BAQEFAASC
            AqEAgkEA1234abcd
            -----END PRIVATE KEY-----
            next line
        """.trimIndent()

        val scrubbed = LogScrubber.scrub(log)

        assertFalse(scrubbed.contains("MIIEvQIBADANBgkqhkiG9w0BAQEFAASC"))
        assertFalse(scrubbed.contains("AqEAgkEA1234abcd"))
        assertTrue(scrubbed.contains("next line"), "обычный текст после блока обязан остаться")
    }

    @Test
    fun `static key tls-auth вырезается`() {
        val log = "-----BEGIN OpenVPN Static key V1-----\nabc123\n-----END OpenVPN Static key V1-----"
        val scrubbed = LogScrubber.scrub(log)

        assertFalse(scrubbed.contains("abc123"))
    }

    @Test
    fun `tls-crypt-v2 с дефисами в метке вырезается`() {
        val log = "-----BEGIN OpenVPN tls-crypt-v2 client key-----\nsecretdata123\n-----END OpenVPN tls-crypt-v2 client key-----"

        val scrubbed = LogScrubber.scrub(log)

        assertFalse(scrubbed.contains("secretdata123"), "метка с дефисами обязана попадать под фильтр")
    }

    @Test
    fun `bearer token вырезается`() {
        val scrubbed = LogScrubber.scrub("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig")

        assertFalse(scrubbed.contains("eyJhbGciOiJIUzI1NiJ9"))
        assertTrue(scrubbed.contains("Bearer [REDACTED]"))
    }

    @Test
    fun `обычная строка не меняется`() {
        val line = "EVENT: CONNECTED via UDP on tun/10.84.0.42/"

        assertEquals(line, LogScrubber.scrub(line), "нечего скрывать — текст остаётся байт в байт")
    }

    @Test
    fun `scrubStream склеивает многострочный блок и вырезает его`() {
        val out = LogScrubber.scrubStream(
            listOf("до", "-----BEGIN PRIVATE KEY-----", "SECRETLINE", "-----END PRIVATE KEY-----", "после"),
        )

        val text = out.joinToString("\n")
        assertFalse(text.contains("SECRETLINE"), "многострочный блок обязан вырезаться целиком")
        assertTrue(text.contains("до") && text.contains("после"), "обычные строки обязаны остаться")
    }
}
