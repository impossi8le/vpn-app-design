package com.impossi8le.vpnapp.feature.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Когда экран обязан признаться, что список — из кэша.
 *
 * §6: сохранённые данные нельзя выдавать за текущее состояние. Функция решает,
 * показывать ли подпись об устаревании, и это решение — логика, а не разметка.
 */
class StalenessNoteTest {

    @Test
    fun `свежий список — подписи нет`() {
        assertNull(
            stalenessNote(fromCache = false, refreshing = false),
            "у свежих данных признаваться не в чем",
        )
    }

    @Test
    fun `данные из кэша в покое помечаются`() {
        // Кэш на экране, обновление ещё не пришло и не идёт — надо сказать, что
        // данные сохранённые. Это и есть защита §6.
        val note = stalenessNote(fromCache = true, refreshing = false)

        assertNotNull(note)
        assertEquals(true, note!!.contains("сохранённые"), "тон честный: данные сохранённые: $note")
    }

    @Test
    fun `во время обновления отдельной подписи нет`() {
        // На экране уже стоит индикатор «Идёт обновление…»; вторая строка про то
        // же самое — шум. Подпись о кэше это состояние покоя.
        assertNull(stalenessNote(fromCache = true, refreshing = true))
    }
}
