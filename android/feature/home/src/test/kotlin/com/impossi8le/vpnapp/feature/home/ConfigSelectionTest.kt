package com.impossi8le.vpnapp.feature.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Автовыбор строки в списке подключений на чистой установке.
 *
 * Защищается три вещи, каждая — из разбора ложных состояний:
 * 1. Сохранённый выбор НЕ перебивается автовыбором (иначе после перезапуска
 *    пользователь получил бы не своё подключение).
 * 2. На пустом списке и на «всё истекло» не выделяется ничего: подсветить
 *    мёртвое значило бы обещать работу, которой не будет.
 * 3. «Первый» — это первый в порядке сервера, а не самый свежий по сроку.
 *
 * Функция чистая, Android-зависимостей нет — тест обычный JUnit.
 */
class ConfigSelectionTest {

    @Test
    fun `на пустом выборе выделяется первое живое подключение`() {
        // Чистая установка: `selectedConfigId` — null, список пришёл.
        val candidates = listOf(available("nl-ams-1"), available("de-fra-1"))

        assertEquals("nl-ams-1", pickDefaultConfigId(candidates, currentId = null))
    }

    @Test
    fun `сохранённый выбор не перебивается`() {
        // Ключевой инвариант. Даже если первое живое — другое, выбранное
        // пользователем остаётся выбранным.
        val candidates = listOf(available("nl-ams-1"), available("de-fra-1"))

        assertEquals("de-fra-1", pickDefaultConfigId(candidates, currentId = "de-fra-1"))
    }

    @Test
    fun `сохранённый выбор не перебивается даже если строка истекла`() {
        // Строка могла истечь между запусками, но решение о смене выбора —
        // не дело автоподстановки: она обязана молчать при непустом выборе.
        val candidates = listOf(available("nl-ams-1"), expired("de-fra-1"))

        assertEquals("de-fra-1", pickDefaultConfigId(candidates, currentId = "de-fra-1"))
    }

    @Test
    fun `все подключения истекли — ничего не выделяется`() {
        // Мёртвому нельзя давать вид рабочего: выбирать нечего.
        val candidates = listOf(expired("nl-ams-1"), expired("de-fra-1"))

        assertNull(pickDefaultConfigId(candidates, currentId = null))
    }

    @Test
    fun `пустой список — ничего не выделяется`() {
        assertNull(pickDefaultConfigId(emptyList(), currentId = null))
    }

    @Test
    fun `истёкшие в начале списка пропускаются в пользу первого живого`() {
        // Порядок сервера задаёт, кого предложить первым, но истёкшее не
        // кандидат вовсе — его пропускаем, а не останавливаемся на нём.
        val candidates = listOf(expired("nl-ams-1"), expired("de-fra-1"), available("fi-hel-1"))

        assertEquals("fi-hel-1", pickDefaultConfigId(candidates, currentId = null))
    }

    @Test
    fun `выбирается первый по порядку сервера, а не самый свежий по сроку`() {
        // Оба живые и лежат в порядке ответа: берём первый, а не тот, что
        // истекает позже. Свежесть тут — выдуманный критерий.
        val candidates = listOf(available("nl-ams-1"), available("de-fra-1"))

        assertEquals("nl-ams-1", pickDefaultConfigId(candidates, currentId = null))
    }

    // --- Вспомогательные фабрики -------------------------------------------

    private fun available(id: String) = ConfigRowState(
        id = id,
        name = "Подключение",
        countryCode = "NL",
        subtitle = "до 01.01.2027 · осталось 60 дней",
        status = ConfigRowStatus.Available,
    )

    private fun expired(id: String) = ConfigRowState(
        id = id,
        name = "Подключение",
        countryCode = "NL",
        subtitle = "Истёк 01.10.2026",
        status = ConfigRowStatus.Expired,
    )
}
