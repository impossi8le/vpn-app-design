package com.impossi8le.vpnapp.feature.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Ссылка входа не должна давать сессию постороннему.
 *
 * Кастомную схему на Android не закрепляет за приложением никто: любой APK может
 * объявить `vpnapp://login` у себя. Полноценная защита — App Links с доменом, но
 * пока домена нет, остаётся не доверять ссылке и сверять код с тем, который мы
 * сами сгенерировали.
 */
class DeepLinkGuardTest {

    @Test
    fun `совпавший код принимается`() {
        val uri = "vpnapp://login?code=a7f3c9d2e1b4"
        assertEquals("a7f3c9d2e1b4", DeepLinkGuard.publicCodeFrom(uri, "a7f3c9d2e1b4"))
    }

    @Test
    fun `чужой код отбрасывается`() {
        // Стороннее приложение объявило нашу схему и подсунуло свой код: если его
        // принять, пользователь уйдёт в чужую операцию входа.
        val uri = "vpnapp://login?code=attacker_code"
        assertNull(DeepLinkGuard.publicCodeFrom(uri, "a7f3c9d2e1b4"))
    }

    @Test
    fun `ссылка без ожидаемой операции отбрасывается`() {
        // Если вход не начинался, принимать ссылку некуда.
        assertNull(DeepLinkGuard.publicCodeFrom("vpnapp://login?code=x", expectedCode = null))
    }

    @Test
    fun `пустая ссылка отбрасывается`() {
        assertNull(DeepLinkGuard.publicCodeFrom(null, "a7f3c9d2e1b4"))
        assertNull(DeepLinkGuard.publicCodeFrom("", "a7f3c9d2e1b4"))
    }

    @Test
    fun `чужая схема отбрасывается`() {
        assertNull(
            DeepLinkGuard.publicCodeFrom("evil://login?code=a7f3c9d2e1b4", "a7f3c9d2e1b4"),
        )
    }

    @Test
    fun `чужой хост отбрасывается`() {
        assertNull(
            DeepLinkGuard.publicCodeFrom("vpnapp://steal?code=a7f3c9d2e1b4", "a7f3c9d2e1b4"),
        )
    }

    @Test
    fun `ссылка без кода отбрасывается`() {
        assertNull(DeepLinkGuard.publicCodeFrom("vpnapp://login", "a7f3c9d2e1b4"))
        assertNull(DeepLinkGuard.publicCodeFrom("vpnapp://login?other=1", "a7f3c9d2e1b4"))
    }

    @Test
    fun `пустой код отбрасывается`() {
        assertNull(DeepLinkGuard.publicCodeFrom("vpnapp://login?code=", "a7f3c9d2e1b4"))
    }

    @Test
    fun `лишние параметры не мешают`() {
        // Реальная ссылка из бота может нести и другие поля.
        val uri = "vpnapp://login?foo=1&code=a7f3c9d2e1b4&bar=2"
        assertEquals("a7f3c9d2e1b4", DeepLinkGuard.publicCodeFrom(uri, "a7f3c9d2e1b4"))
    }

    @ParameterizedTest(name = "схема {0} не проходит")
    @ValueSource(strings = ["http://login", "https://login", "VPNAPP://login", "vpnapp:/login"])
    fun `похожие но чужие схемы не проходят`(prefix: String) {
        // Похожесть строки не должна приниматься за совпадение: сравнение точное.
        assertNull(DeepLinkGuard.publicCodeFrom("$prefix?code=a7f3c9d2e1b4", "a7f3c9d2e1b4"))
    }
}
