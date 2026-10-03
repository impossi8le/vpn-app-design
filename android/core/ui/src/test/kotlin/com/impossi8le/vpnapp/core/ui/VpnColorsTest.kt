package com.impossi8le.vpnapp.core.ui

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Токены как данные: цвета можно проверить, не рендеря экран.
 *
 * Проверяется не только «цвет верный», но и семантика: зелёный, янтарный и
 * красный не должны совпадать друг с другом и не должны совпадать с акцентом.
 * Иначе состояние можно показать цветом, который означает другое.
 */
class VpnColorsTest {

    @Test
    fun `цвета совпадают с макетом`() {
        assertEquals(Color(0xFF0A0C10), VpnColors.Void)
        assertEquals(Color(0xFF12151B), VpnColors.Carbon)
        assertEquals(Color(0xFF1A1E26), VpnColors.Obsidian)
        assertEquals(Color(0xFF262B34), VpnColors.Hairline)
        assertEquals(Color(0xFF333A45), VpnColors.Smoke)
        assertEquals(Color(0xFF5A6472), VpnColors.Edge)
        assertEquals(Color(0xFF7F8A99), VpnColors.Ash)
        assertEquals(Color(0xFF8B95A3), VpnColors.TextSecondary)
        assertEquals(Color(0xFFAAB4C2), VpnColors.Mist)
        assertEquals(Color(0xFFE8ECF2), VpnColors.Bone)
        assertEquals(Color(0xFF6FB3FF), VpnColors.Ice)
        assertEquals(Color(0xFF3DDC97), VpnColors.Green)
        assertEquals(Color(0xFFFFB454), VpnColors.Amber)
        assertEquals(Color(0xFFFF5F6D), VpnColors.Red)
    }

    @Test
    fun `три состояния различимы между собой`() {
        // Если два состояния красятся одинаково, пользователь их не отличит.
        assertNotEquals(VpnColors.Green, VpnColors.Amber)
        assertNotEquals(VpnColors.Green, VpnColors.Red)
        assertNotEquals(VpnColors.Amber, VpnColors.Red)
    }

    @Test
    fun `акцент не совпадает ни с одним состоянием`() {
        // Ice — только выделение и фокус. Совпади он с зелёным, фокус выглядел бы
        // как подтверждённая защита.
        assertNotEquals(VpnColors.Ice, VpnColors.Green)
        assertNotEquals(VpnColors.Ice, VpnColors.Amber)
        assertNotEquals(VpnColors.Ice, VpnColors.Red)
    }

    @Test
    fun `радиусы совпадают с макетом`() {
        assertEquals(14, VpnRadii.Card)
        assertEquals(12, VpnRadii.Button)
        assertEquals(8, VpnRadii.Small)
    }

    @Test
    fun `поверхности не пересекаются`() {
        // Фон и поверхности обязаны различаться, иначе карточка не читается.
        val surfaces = listOf(VpnColors.Void, VpnColors.Carbon, VpnColors.Obsidian)
        assertEquals(surfaces.size, surfaces.toSet().size, "поверхности должны быть разными цветами")
    }

    @Test
    fun `никакой токен не равен прозрачному`() {
        // Незаданный цвет в Compose — это Unspecified, и он визуально прозрачен.
        val all = listOf(
            VpnColors.Void, VpnColors.Carbon, VpnColors.Obsidian,
            VpnColors.Hairline, VpnColors.Smoke, VpnColors.Edge,
            VpnColors.Ash, VpnColors.TextSecondary, VpnColors.Mist, VpnColors.Bone,
            VpnColors.Ice, VpnColors.Green, VpnColors.Amber, VpnColors.Red,
        )
        all.forEach { color ->
            assertTrue(color.alpha == 1f, "токен $color должен быть непрозрачным")
            assertTrue(color != Color.Unspecified, "токен не должен быть Unspecified")
        }
    }
}
