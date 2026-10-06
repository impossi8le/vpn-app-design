package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ConfigList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Правило «неудачное обновление не затирает кэш».
 *
 * Защищаемый инвариант §6: сохранённый список — последнее, что мы ЗНАЕМ о
 * подписке. Стереть его из-за сетевой ошибки значит остаться без данных на
 * холодном старте — ровно то, от чего кэш и заводится.
 */
class ConfigCacheStoreTest {

    private val cached = ConfigList(chatId = 1L, configs = emptyList())
    private val fresh = ConfigList(chatId = 1L, configs = emptyList())

    @Test
    fun `успешное обновление заменяет кэш`() {
        assertEquals(fresh, cacheAfterRefresh(cached, fresh))
    }

    @Test
    fun `неудачное обновление сохраняет прежний кэш`() {
        assertEquals(
            cached,
            cacheAfterRefresh(cached, fetched = null),
            "сеть отвалилась — данные не теряем",
        )
    }

    @Test
    fun `первое обновление без кэша и без ответа оставляет пусто`() {
        assertNull(cacheAfterRefresh(current = null, fetched = null))
    }

    @Test
    fun `первое успешное обновление заполняет пустой кэш`() {
        assertEquals(fresh, cacheAfterRefresh(current = null, fetched = fresh))
    }
}
