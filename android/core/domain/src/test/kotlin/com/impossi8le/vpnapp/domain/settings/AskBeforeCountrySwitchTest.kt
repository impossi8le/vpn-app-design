package com.impossi8le.vpnapp.domain.settings

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Решение «спрашивать ли подтверждение смены страны».
 *
 * Табличный тест: обе настройки независимы, и связку проще держать таблицей, чем
 * четырьмя отдельными методами — ошибка в одной ячейке видна сразу.
 */
class AskBeforeCountrySwitchTest {

    @ParameterizedTest(name = "тумблер={0}, не спрашивать={1} → спрашивать={2}")
    @CsvSource(
        "true,  false, true",   // тумблер включён, галочки нет — спрашиваем
        "false, false, false",  // тумблер выключен — не спрашиваем
        "true,  true,  false",  // отказ галочкой — не спрашиваем
        "false, true,  false",  // оба выключены — не спрашиваем
    )
    fun `подтверждение спрашивается по обеим настройкам`(
        confirm: Boolean,
        dontAsk: Boolean,
        expected: Boolean,
    ) {
        assertTrue(expected == askBeforeCountrySwitch(confirm, dontAsk))
    }

    @Test
    fun `по умолчанию тумблер включён и вопрос задаётся`() {
        // Дефолт «подтверждать» — безопасная сторона: молчаливый разрыв
        // соединения в момент звонка пользователь не увидит без предупреждения.
        assertTrue(askBeforeCountrySwitch(confirmCountrySwitch = true, dontAskAgain = false))
    }

    @Test
    fun `отказ галочкой снимает вопрос`() {
        assertFalse(askBeforeCountrySwitch(confirmCountrySwitch = true, dontAskAgain = true))
    }
}
