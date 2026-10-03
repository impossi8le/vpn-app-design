package com.impossi8le.vpnapp.domain.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Уровень логирования ядра OpenVPN.
 *
 * При `verb >= 3` ядро печатает тела PEM, то есть приватный ключ уезжает в
 * logcat — откуда его заберёт любой, у кого есть доступ к логам или крэш-отчёту.
 * Это не стилистика, а утечка секрета, поэтому ограничение закреплено типом.
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
}
