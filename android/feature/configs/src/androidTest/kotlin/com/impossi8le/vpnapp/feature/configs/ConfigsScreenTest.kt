package com.impossi8le.vpnapp.feature.configs

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Список подключений как его видит пользователь.
 *
 * Имена методов БЕЗ пробелов и обратных кавычек: эти тесты компилируются в DEX,
 * а D8 до версии 040 запрещает пробелы в именах методов.
 *
 * Три инварианта §3 контракта проверяются глазами пользователя: свой статус у
 * каждого подключения, пустой список как состояние (а не ошибка), отсутствие
 * призывов к покупке.
 */
class ConfigsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun config(id: String, status: SubscriptionStatus) = ConfigSummary(
        id = id,
        name = "Нидерланды · $id",
        countryCode = "NL",
        city = id,
        startDateEpochSeconds = 1_700_000_000L,
        endDateEpochSeconds = 1_800_000_000L,
        status = status,
    )

    @Test
    fun eachRowShowsItsOwnStatus() {
        compose.setContent {
            ConfigsScreen(
                state = ConfigsUiState.Ready(
                    listOf(
                        config("ams", SubscriptionStatus.ACTIVE),
                        config("rtm", SubscriptionStatus.EXPIRED),
                    ),
                ),
                onSelect = {},
            )
        }

        // Истёкшее подключение не скрывает действующее: у каждого своя подпись.
        compose.onNodeWithText("Работает").assertIsDisplayed()
        compose.onNodeWithText("Подписка истекла").assertIsDisplayed()
    }

    @Test
    fun tappingActiveConfigSelectsIt() {
        var selected: ConfigSummary? = null
        compose.setContent {
            ConfigsScreen(
                state = ConfigsUiState.Ready(listOf(config("ams", SubscriptionStatus.ACTIVE))),
                onSelect = { selected = it },
            )
        }

        compose.onNodeWithTag(configRowTag("ams")).performClick()

        assertEquals("ams", selected?.id)
    }

    @Test
    fun tappingExpiredConfigDoesNothing() {
        // Сервер не отдаст по нему конфиг, поэтому и выбирать нечего.
        var selected: ConfigSummary? = null
        compose.setContent {
            ConfigsScreen(
                state = ConfigsUiState.Ready(listOf(config("rtm", SubscriptionStatus.EXPIRED))),
                onSelect = { selected = it },
            )
        }

        compose.onNodeWithTag(configRowTag("rtm")).performClick()

        assertEquals(null, selected)
    }

    @Test
    fun emptyListReadsAsNoSubscriptionsRatherThanError() {
        compose.setContent {
            ConfigsScreen(state = ConfigsUiState.Empty, onSelect = {})
        }

        compose.onNodeWithTag(CONFIGS_EMPTY_TAG).assertIsDisplayed()
        compose.onNodeWithText("Подключений нет").assertIsDisplayed()
    }

    @Test
    fun failedLoadShowsRetryableMessage() {
        compose.setContent {
            ConfigsScreen(state = ConfigsUiState.Failed(retryable = true), onSelect = {})
        }

        compose.onNodeWithTag(CONFIGS_ERROR_TAG).assertIsDisplayed()
        compose.onNodeWithText("Не удалось загрузить. Попробуйте снова").assertIsDisplayed()
    }

    @Test
    fun nonRetryableFailureDoesNotPromiseARetry() {
        // Обещать повтор там, где он не поможет, значит вводить в заблуждение.
        compose.setContent {
            ConfigsScreen(state = ConfigsUiState.Failed(retryable = false), onSelect = {})
        }

        compose.onNodeWithText("Доступ закрыт").assertIsDisplayed()
    }

    @Test
    fun revokedAndExpiredReadDifferently() {
        // Отозванное не вернётся само, истёкшее можно продлить — для пользователя
        // это разные ситуации.
        compose.setContent {
            ConfigsScreen(
                state = ConfigsUiState.Ready(
                    listOf(
                        config("a", SubscriptionStatus.EXPIRED),
                        config("b", SubscriptionStatus.REVOKED),
                    ),
                ),
                onSelect = {},
            )
        }

        compose.onNodeWithText("Подписка истекла").assertIsDisplayed()
        compose.onNodeWithText("Доступ отозван").assertIsDisplayed()
    }

    @Test
    fun screenOffersNoPurchaseCallToAction() {
        // В ответе сервера поля для покупки нет намеренно, и клиент не рендерит
        // «продлить». Проверяем, что экран не выдумывает его сам.
        compose.setContent {
            ConfigsScreen(
                state = ConfigsUiState.Ready(listOf(config("x", SubscriptionStatus.EXPIRED))),
                onSelect = {},
            )
        }

        listOf("Продлить", "Купить", "Оформить подписку").forEach { text ->
            compose.onNodeWithText(text, substring = true).assertDoesNotExist()
        }
    }
}
