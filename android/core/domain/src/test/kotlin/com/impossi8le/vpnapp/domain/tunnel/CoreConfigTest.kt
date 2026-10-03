package com.impossi8le.vpnapp.domain.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Уровень логирования ядра OpenVPN.
 *
 * При `verb >= 3` ядро печатает тела PEM, то есть приватный ключ уезжает в
 * logcat — откуда его заберёт любой, у кого есть доступ к логам или крэш-отчёту.
 * Это не стилистика, а утечка секрета, поэтому ограничение закреплено типом.
 *
 * Отдельно проверяется главный случай: **реальный боевой профиль содержит
 * `verb 3`** (см. память о формате `.ovpn`). То есть опасное значение приходит
 * не от нашего кода, а из файла, который присылает сервер. Тип защищает только
 * в том случае, если профиль не может его переопределить, — а это надо
 * доказывать, а не предполагать.
 */
class CoreConfigTest {

    @Test
    fun `уровень выше 1 не собирается`() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreConfig(verb = 3)
        }
    }

    @Test
    fun `уровень 1 — максимум`() {
        assertEquals(1, CoreConfig(verb = 1).verb)
    }

    @Test
    fun `уровень по умолчанию — 1`() {
        assertEquals(1, CoreConfig().verb)
    }

    @Test
    fun `нулевой уровень допустим`() {
        assertEquals(0, CoreConfig(verb = 0).verb)
    }

    @Test
    fun `конфиг не просит сохранять ключ в лог`() {
        val config = CoreConfig()
        assertFalse(config.verb >= 3, "verb >= 3 печатает тела PEM — приватный ключ уедет в logcat")
        assertTrue(config.verb <= 1)
    }

    // --- Профиль не вправе поднять уровень логирования ---

    @ParameterizedTest(name = "verb {0} из профиля понижается")
    @ValueSource(ints = [2, 3, 4, 6, 9, 11])
    fun `опасный verb из профиля понижается до безопасного`(profileVerb: Int) {
        val config = CoreConfig.fromProfile(profileVerb)

        assertTrue(
            config.verb <= 1,
            "профиль прислал verb $profileVerb — это печатает PEM, приватный ключ уехал бы в logcat",
        )
    }

    @Test
    fun `реальный боевой профиль с verb 3 не пробивает запрет`() {
        // Настоящий продакшен-.ovpn содержит `verb 3`. Если бы профиль мог
        // задать уровень, каждый запуск печатал бы ключ в logcat.
        val config = CoreConfig.fromProfile(profileVerb = 3)

        assertEquals(1, config.verb)
    }

    @Test
    fun `профиль без verb оставляет уровень по умолчанию`() {
        assertEquals(1, CoreConfig.fromProfile(profileVerb = null).verb)
    }

    @Test
    fun `безопасный verb из профиля сохраняется`() {
        assertEquals(0, CoreConfig.fromProfile(profileVerb = 0).verb)
        assertEquals(1, CoreConfig.fromProfile(profileVerb = 1).verb)
    }

    @Test
    fun `уровень не может стать отрицательным из-за профиля`() {
        // Отрицательный verb бессмысленен и мог бы сломать ядро.
        assertTrue(CoreConfig.fromProfile(profileVerb = -5).verb >= 0)
    }
}
