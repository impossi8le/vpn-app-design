package com.impossi8le.vpnapp.feature.account

import com.impossi8le.vpnapp.domain.config.ConfigList
import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `чужие поля переносятся нетронутыми`() {
        val base = DefaultAccountScreenState.copy(
            telegramId = "123456",
            telegramIdRevealed = true,
            buildExpiryDate = "29.12.2026",
            autoConnect = true,
            askFaceId = true,
            confirmCountrySwitch = false,
        )

        val state = ConfigList(chatId = 1L, configs = emptyList()).toAccountScreenState(base)

        // Функция не знает про эти поля и не имеет права их менять.
        assertEquals("123456", state.telegramId)
        assertEquals(true, state.telegramIdRevealed)
        assertEquals("29.12.2026", state.buildExpiryDate)
        assertEquals(true, state.autoConnect)
        assertEquals(true, state.askFaceId)
        assertEquals(false, state.confirmCountrySwitch)
    }
}
