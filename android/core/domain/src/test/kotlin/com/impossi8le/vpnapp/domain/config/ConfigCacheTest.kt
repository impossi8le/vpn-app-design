package com.impossi8le.vpnapp.domain.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Отбор конфигов из кэша для первого кадра — по локальной дате.
 *
 * Защищается ровно одно: сохранённый `ACTIVE`, срок которого уже вышел, НЕ
 * попадает в список доступных. Без этого кэш предлагал бы выбрать конфиг,
 * который перестал работать, пока приложение было закрыто, — та же ложная
 * доступность, против которой написан `toRowState`.
 *
 * Время передаётся параметром: функция чистая, тест детерминирован.
 */
class ConfigCacheTest {

    private val now = 1_700_000_000L

    @Test
    fun `кэша нет — отбор пуст`() {
        val selection = cachedConfigSelection(cache = null, nowEpochSeconds = now)

        assertTrue(selection.configs.isEmpty())
        assertFalse(selection.fromCache, "без кэша нечего помечать как сохранённое")
    }

    @Test
    fun `действующий конфиг из кэша отдаётся`() {
        val cache = list(active(id = "a", endOffsetDays = 10))

        val selection = cachedConfigSelection(cache, now)

        assertEquals(listOf("a"), selection.configs.map { it.id })
        assertTrue(selection.fromCache)
    }

    @Test
    fun `статус ACTIVE но срок вышел — конфиг не показывается`() {
        // Ключевой инвариант. Сервер в прошлый раз сказал ACTIVE, но end_date
        // уже прошёл, пока приложение было закрыто. Локальная дата обязана
        // скрыть конфиг, иначе экран предложит мёртвое подключение.
        val cache = list(
            active(id = "живой", endOffsetDays = 5),
            active(id = "протух", endOffsetDays = -1),
        )

        val selection = cachedConfigSelection(cache, now)

        assertEquals(listOf("живой"), selection.configs.map { it.id })
    }

    @Test
    fun `срок в точности сейчас считается истёкшим`() {
        // Граница строгая: `end == now` — уже не «работает». Та же политика, что
        // у `decideProfileUse` в подготовке профиля; расходиться они не должны.
        val cache = list(active(id = "граница", endOffsetDays = 0))

        assertTrue(cachedConfigSelection(cache, now).configs.isEmpty())
    }

    @Test
    fun `отозванный конфиг не показывается даже в пределах срока`() {
        // REVOKED живёт до даты окончания, но не работает. Только по дате его не
        // отсечь — нужен и статус.
        val cache = list(
            active(id = "rev", endOffsetDays = 30, status = SubscriptionStatus.REVOKED),
        )

        assertTrue(cachedConfigSelection(cache, now).configs.isEmpty())
    }

    @Test
    fun `порядок сервера сохраняется`() {
        val cache = list(
            active(id = "b", endOffsetDays = 30),
            active(id = "a", endOffsetDays = 30),
            active(id = "c", endOffsetDays = 30),
        )

        assertEquals(
            listOf("b", "a", "c"),
            cachedConfigSelection(cache, now).configs.map { it.id },
            "сортировка — решение представления, не отбора",
        )
    }

    @Test
    fun `весь кэш просрочен — данных из кэша нет`() {
        // Пустой результат — это «показывать нечего», а не «данные из кэша»:
        // по нему нельзя восстанавливать счётчики аккаунта.
        val cache = list(active(id = "x", endOffsetDays = -1))

        val selection = cachedConfigSelection(cache, now)

        assertTrue(selection.configs.isEmpty())
        assertFalse(selection.fromCache, "просроченный кэш не выдаём за данные из кэша")
    }

    // --- Вспомогательные фабрики -------------------------------------------

    private fun list(vararg configs: ConfigSummary) = ConfigList(chatId = 1L, configs = configs.toList())

    private fun active(
        id: String,
        endOffsetDays: Long,
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    ) = ConfigSummary(
        id = id,
        name = "страна · город",
        countryCode = "NL",
        city = "город",
        startDateEpochSeconds = now - 100L * DAY,
        endDateEpochSeconds = now + endOffsetDays * DAY,
        status = status,
    )

    private companion object {
        const val DAY = 86_400L
    }
}
