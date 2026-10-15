package com.impossi8le.vpnapp.domain.diagnostics

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Обезличивание профиля перед отправкой в отчёте.
 *
 * Профиль содержит приватный ключ и ключ `tls-auth` — это доступ к серверу, а не
 * «настройка». Отправка их на приёмный сервер означала бы передачу доступа
 * третьей стороне, поэтому ключевые блоки вырезаются здесь, на устройстве.
 *
 * Проверяется на боевой фикстуре (`anonymized-production.ovpn`): она повторяет
 * реальный профиль директива в директиву, поэтому тест ловит именно то, что
 * уйдёт на сервер.
 */
class ConfigAnonymizerTest {

    private val productionProfile = """
        client
        dev tun
        proto udp
        remote 192.0.2.10 443 udp
        remote-random
        cipher AES-256-CBC
        data-ciphers AES-256-GCM:AES-128-GCM
        auth SHA256
        resolv-retry infinite
        nobind
        persist-key
        persist-tun
        remote-cert-tls server
        key-direction 1
        verb 1
        redirect-gateway def1 bypass-dhcp
        <ca>
        -----BEGIN CERTIFICATE-----
        MIICertDataAAA
        -----END CERTIFICATE-----
        </ca>
        <cert>
        -----BEGIN CERTIFICATE-----
        MIIClientCertBBB
        -----END CERTIFICATE-----
        </cert>
        <key>
        -----BEGIN PRIVATE KEY-----
        SUPERSECRETKEYDATA
        -----END PRIVATE KEY-----
        </key>
        <tls-auth>
        -----BEGIN OpenVPN Static key V1-----
        abc123statickey
        -----END OpenVPN Static key V1-----
        </tls-auth>
    """.trimIndent()

    @Test
    fun `приватный ключ не остаётся в обезличенном профиле`() {
        val result = ConfigAnonymizer.anonymize(productionProfile)

        assertFalse(result.contains("SUPERSECRETKEYDATA"), "тело приватного ключа — утечка доступа")
        assertFalse(result.contains("abc123statickey"), "ключ tls-auth — тот же класс секрета")
    }

    @Test
    fun `сертификаты не остаются`() {
        val result = ConfigAnonymizer.anonymize(productionProfile)

        assertFalse(result.contains("MIICertDataAAA"))
        assertFalse(result.contains("MIIClientCertBBB"))
    }

    @Test
    fun `структура подключения сохраняется`() {
        val result = ConfigAnonymizer.anonymize(productionProfile)

        assertTrue(result.contains("remote 192.0.2.10 443 udp"), "адрес сервера нужен для диагностики")
        assertTrue(result.contains("proto udp"))
        assertTrue(result.contains("cipher AES-256-CBC"))
        assertTrue(result.contains("data-ciphers AES-256-GCM:AES-128-GCM"))
        assertTrue(result.contains("redirect-gateway def1 bypass-dhcp"), "маршрутизация — частая причина сбоя")
    }

    @Test
    fun `теги блоков тоже убираются, чтобы не выглядело как обрыв`() {
        val result = ConfigAnonymizer.anonymize(productionProfile)

        // Метка удаления сама упоминает имя тега, поэтому проверяем отсутствие
        // тега как самостоятельной строки, а не подстроки.
        assertFalse(
            result.lines().any { it.trim() == "<key>" },
            "пустой тег выглядел бы как испорченный профиль",
        )
        assertFalse(result.lines().any { it.trim() == "<tls-auth>" })
        assertTrue(result.contains("# <key> удалён"), "факт удаления виден, а не замаскирован")
    }

    @Test
    fun `профиль без ключевых блоков не меняется`() {
        val plain = "client\ndev tun\nremote 192.0.2.10 443\nverb 1\n"
        val result = ConfigAnonymizer.anonymize(plain)

        assertTrue(result.contains("dev tun"))
        assertTrue(result.contains("remote 192.0.2.10 443"))
        assertFalse(result.contains("удалён"), "нечего удалять — метки быть не должно")
    }
}
