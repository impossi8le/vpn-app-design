package com.impossi8le.vpnapp.feature.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Формат обратного отсчёта в подписи ожидания входа.
 *
 * Функция крошечная, но именно на таких и экономили: `5:0` вместо `5:00`,
 * отрицательное значение на исходе срока или `300` без разбора на минуты —
 * каждая из этих ошибок незаметна, пока не случится у пользователя. Здесь они
 * проверяются до того, как отсчёт отрисуется на экране.
 */
class CountdownFormatTest {

    @ParameterizedTest(name = "{0} секунд -> {1}")
    @CsvSource(
        // Полный срок, первая и последняя минуты, одиночные секунды — все места,
        // где padStart и целочисленное деление ломаются тихо.
        "300, 5:00",
        "299, 4:59",
        "60, 1:00",
        "59, 0:59",
        "9, 0:09",
        "1, 0:01",
        "0, 0:00",
    )
    fun `секунды форматируются как M_SS`(seconds: Int, expected: String) {
        assertEquals(expected, formatCountdown(seconds))
    }

    @Test
    fun `отрицательное значение не уходит в минус`() {
        // Отсчёт на экране ограничен нулём, но функция не должна зависеть от
        // этого: `-1` обязан показать 0:00, а не `-1:59`.
        assertEquals("0:00", formatCountdown(-1))
        assertEquals("0:00", formatCountdown(-120))
    }
}
