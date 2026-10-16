package com.impossi8le.vpnapp.feature.account

import com.impossi8le.vpnapp.domain.config.ConfigList
import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Экран «Аккаунт» показывал «0 из 0» и «—» при живой подписке: список из `/me`
 * питал только перечень подключений, а поля аккаунта никто не заполнял. Эти
 * тесты фиксируют, что ответ `/me` теперь наполняет экран, и что отсутствие
 * полей даёт прочерк, а не выдуманную дату.
 *
 * Тест чистый: время приходит из данных, а не из часов, поэтому детерминирован
 * на любой машине и в любой день.
 */
class AccountMappingTest {

    private fun config(status: SubscriptionStatus, id: String = "c") = ConfigSummary(
        id = id,
        name = id,
        countryCode = "DE",
        city = "Франкфурт",
        startDateEpochSeconds = 0L,
        endDateEpochSeconds = 0L,
        status = status,
    )

    private fun emptyConfigList() = ConfigList(
        chatId = 1L,
        configs = emptyList(),
        connectionsLimit = 0,
        subscriptionUntilEpochSeconds = null,
    )

    @Test
    fun `из me наполняются счётчики и срок подписки`() {
        val until = Instant.parse("2026-11-01T12:45:56Z").epochSecond
        val list = ConfigList(
            chatId = 1L,
            connectionsLimit = 3,
            subscriptionUntilEpochSeconds = until,
            configs = listOf(
                config(SubscriptionStatus.ACTIVE, "a"),
                config(SubscriptionStatus.ACTIVE, "b"),
                config(SubscriptionStatus.EXPIRED, "c"),
            ),
        )

        val state = list.toAccountScreenState(DefaultAccountScreenState)

        assertEquals(2, state.activeConnections)
        assertEquals(1, state.expiredConnections)
        assertEquals(3, state.totalConnections)
        val expectedDate = Instant.ofEpochSecond(until).atZone(ZoneId.systemDefault())
            .toLocalDate().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        assertEquals(expectedDate, state.subscriptionUntil)
    }

    @Test
    fun `revoked считается истёкшим — лимит занят только действующими`() {
        val list = ConfigList(
            chatId = 1L,
            connectionsLimit = 5,
            configs = listOf(
                config(SubscriptionStatus.ACTIVE, "a"),
                config(SubscriptionStatus.REVOKED, "b"),
            ),
        )

        val state = list.toAccountScreenState(DefaultAccountScreenState)

        assertEquals(1, state.activeConnections)
        assertEquals(1, state.expiredConnections)
    }

    @Test
    fun `отсутствие срока подписки даёт прочерк, а не 1970`() {
        val list = ConfigList(chatId = 1L, configs = emptyList())

        val state = list.toAccountScreenState(DefaultAccountScreenState)

        assertEquals(SUBSCRIPTION_UNKNOWN, state.subscriptionUntil)
        assertEquals(0, state.totalConnections)
    }

    @Test
    fun `Telegram ID берётся из chat_id ответа me`() {
        // Раньше на экране стояло выдуманное «•••• 4821», и «Показать» раскрывало
        // его как чужой ID. Теперь значение приходит из ответа: chat_id — это и
        // есть Telegram ID (§3 контракта).
        val state = ConfigList(chatId = 377182640L, configs = emptyList())
            .toAccountScreenState(DefaultAccountScreenState)

        assertEquals("377182640", state.telegramId)
    }

    @Test
    fun `нет chat_id в ответе — честное отсутствие, а не выдуманное число`() {
        // Разбор без `chat_id` кладёт 0L. Ноль — это «поля нет», и показывать
        // его как ID пользователя нельзя.
        val state = ConfigList(chatId = 0L, configs = emptyList())
            .toAccountScreenState(DefaultAccountScreenState)

        assertNull(state.telegramId)
    }

    @Test
    fun `умолчание экрана не содержит выдуманного Telegram ID`() {
        assertNull(DefaultAccountScreenState.telegramId)
    }

    @Test
    fun `раскрытие показывает ID, скрытие маскирует, отсутствие — прочерк`() {
        val known = DefaultAccountScreenState.copy(telegramId = "377182640")
        assertEquals("377182640", telegramIdDisplay(known.copy(telegramIdRevealed = true)))
        assertEquals("••••••••", telegramIdDisplay(known.copy(telegramIdRevealed = false)))

        // Данных нет — прочерк, и никакой маски: «скрыто» читалось бы как «есть».
        assertEquals("—", telegramIdDisplay(known.copy(telegramId = null, telegramIdRevealed = true)))
    }

    @Test
    fun `чужие поля переносятся нетронутыми`() {
        val base = DefaultAccountScreenState.copy(
            telegramIdRevealed = true,
            confirmCountrySwitch = false,
        )

        val state = ConfigList(chatId = 1L, configs = emptyList()).toAccountScreenState(base)

        // Функция не знает про эти поля и не имеет права их менять.
        assertEquals(true, state.telegramIdRevealed)
        assertEquals(false, state.confirmCountrySwitch)
    }

    @Test
    fun `загрузка me не сбрасывает раскрытие id`() {
        // `toAccountScreenState` переносит поля, которых не знает, из `base`.
        // Раскрытие — именно такое поле: пользователь нажал «Показать», пришёл
        // ответ `/me`, и маска не должна вернуться сама.
        val base = DefaultAccountScreenState.copy(telegramIdRevealed = true)

        val after = emptyConfigList().toAccountScreenState(base)

        assertEquals(true, after.telegramIdRevealed)
    }
}
