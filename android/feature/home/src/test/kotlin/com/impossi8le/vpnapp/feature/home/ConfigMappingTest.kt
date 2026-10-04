package com.impossi8le.vpnapp.feature.home

import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Преобразование ответа сервера в строку списка подключений.
 *
 * Главное, что здесь защищается, — то же правило, что чинили на UX-ревью
 * макета («Истёкший конфиг больше не может быть активным»): истёкшая подписка
 * ОБЯЗАНА получить [ConfigRowStatus.Expired]. Если кто-то однажды сведёт все
 * непустые подписки к «Доступно», экран снова начнёт предлагать выбрать то, что
 * уже не работает, — а это ровно та ложная уверенность, против которой написан
 * инвариант.
 *
 * Время передаётся параметром: функция чистая, и тест обязан быть
 * детерминированным, а не зависеть от момента запуска.
 */
class ConfigMappingTest {

    // Фиксированная точка отсчёта: «сейчас» для всех тестов одно и то же.
    private val now = 1_700_000_000L

    @Test
    fun `активный конфиг становится доступным`() {
        val row = active().toRowState(now)

        // Сервер сказал ACTIVE — строка обязана быть выбираемой.
        assertEquals(ConfigRowStatus.Available, row.status)
    }

    @Test
    fun `истёкший конфиг получает Expired`() {
        val row = expired().toRowState(now)

        // Ключевой инвариант. Не Available и не Active: истёкшее подключение
        // нельзя предлагать к выбору.
        assertEquals(
            ConfigRowStatus.Expired,
            row.status,
            "истёкшая подписка не имеет права выглядеть рабочей",
        )
    }

    @Test
    fun `название и код страны не теряются`() {
        val row = active().toRowState(now)

        // Данные страны пользователю нужны дословно: по ним он узнаёт точку
        // выхода. Потеря или подмена тут сбивает с толку сильнее пустой строки.
        assertEquals("Нидерланды · Амстердам", row.name)
        assertEquals("NL", row.countryCode)
        assertEquals("nl-ams-1", row.id)
    }

    @Test
    fun `подпись действующего подключения показывает срок, а не пустоту`() {
        // endDate ровно на 42 дня вперёд — считаем ожидаемую подпись точно.
        val row = active(endOffsetDays = 42).toRowState(now)

        assertTrue(row.subtitle.startsWith("до "), "подпись обязана начинаться со срока: ${row.subtitle}")
        assertTrue(row.subtitle.contains("осталось 42 дня"), "остаток счёта дней: ${row.subtitle}")
    }

    @Test
    fun `подпись истёкшего говорит об истечении, а не о работе`() {
        val row = expired().toRowState(now)

        // «Работает до …» на истёкшем — то самое противоречие, которое макет
        // запрещал. Проверяем и наличие «Истёк», и отсутствие «осталось».
        assertTrue(row.subtitle.startsWith("Истёк "), "истёкшее подписывается прямо: ${row.subtitle}")
        assertFalse(
            row.subtitle.contains("осталось"),
            "у истёкшего не может быть остатка срока: ${row.subtitle}",
        )
    }

    @Test
    fun `ни один неактивный статус не выглядит доступным`() {
        // Не только EXPIRED: отозванная и ожидающая подписки тоже не дают
        // выбрать подключение. Строка списка выбираемой быть не должна.
        listOf(SubscriptionStatus.EXPIRED, SubscriptionStatus.REVOKED, SubscriptionStatus.PENDING)
            .forEach { status ->
                val row = active(status = status).toRowState(now)
                assertFalse(
                    row.status == ConfigRowStatus.Available,
                    "статус $status не должен становиться Available",
                )
            }
    }

    @Test
    fun `список преобразуется в том же порядке`() {
        val rows = listOf(active(id = "a"), active(id = "b"), expired(id = "c")).toRowStates(now)

        // Порядок задаёт сервер: перестановка — это решение представления, и
        // оно должно быть явным, а не побочным эффектом преобразования.
        assertEquals(listOf("a", "b", "c"), rows.map { it.id })
        assertEquals(ConfigRowStatus.Expired, rows.last().status)
    }

    // --- Вспомогательные фабрики -------------------------------------------

    private fun active(
        id: String = "nl-ams-1",
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        endOffsetDays: Long = 60,
    ) = ConfigSummary(
        id = id,
        name = "Нидерланды · Амстердам",
        countryCode = "NL",
        city = "Амстердам",
        startDateEpochSeconds = now - 30L * DAY,
        endDateEpochSeconds = now + endOffsetDays * DAY,
        status = status,
    )

    private fun expired(id: String = "nl-rtm-1") = ConfigSummary(
        id = id,
        name = "Нидерланды · Роттердам",
        countryCode = "NL",
        city = "Роттердам",
        startDateEpochSeconds = now - 200L * DAY,
        // Дата окончания в прошлом — иначе «истёкший» был бы нечестным.
        endDateEpochSeconds = now - 20L * DAY,
        status = SubscriptionStatus.EXPIRED,
    )

    private companion object {
        const val DAY = 86_400L
    }
}
