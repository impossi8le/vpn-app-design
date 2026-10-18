package com.impossi8le.vpnapp.feature.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Экран «Этот Telegram не привязан к подписке» как его видит пользователь.
 *
 * Имена методов БЕЗ пробелов и обратных кавычек: эти тесты компилируются в DEX,
 * а D8 до версии 040 запрещает пробелы в именах методов.
 *
 * Два инварианта: экран объясняет, а не пугает (серое вместо красного), и не
 * продаёт — призыв к покупке в интерфейсе запрещён (анти-стиринг 3.1.1).
 */
class NoSubscriptionScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(
        onRefresh: () -> Unit = {},
        onSwitchTelegram: () -> Unit = {},
    ) {
        compose.setContent {
            NoSubscriptionScreen(onRefresh = onRefresh, onSwitchTelegram = onSwitchTelegram)
        }
    }

    @Test
    fun titleReadsAsStateNotAsError() {
        setScreen()

        compose.onNodeWithTag(NO_SUBSCRIPTION_TITLE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Этот Telegram", substring = true).assertIsDisplayed()
        compose.onNodeWithText("не привязан к подписке", substring = true).assertIsDisplayed()
    }

    @Test
    fun explainsWhereToGetASubscriptionWithoutSellingIt() {
        // Нейтральный текст говорит, что оформление живёт в боте, но кнопки
        // «купить» нет: продавать из интерфейса приложения нельзя.
        setScreen()

        compose
            .onNodeWithText("подписка оформляется в Telegram-боте", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun screenOffersNoPurchaseCallToAction() {
        setScreen()

        listOf("Купить", "Оформить", "Продлить", "Оплатить").forEach { word ->
            compose.onNodeWithText(word, substring = true).assertDoesNotExist()
        }
    }

    @Test
    fun hintAboutAnotherTelegramIsExplained() {
        // Оплата с другого аккаунта — частая причина, и о ней сказано заранее.
        setScreen()

        compose
            .onNodeWithText("Оплата могла быть с другого Telegram", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun primaryActionRefreshesData() {
        var refreshed = false
        setScreen(onRefresh = { refreshed = true })

        compose.onNodeWithTag(NO_SUBSCRIPTION_REFRESH_TAG).performClick()

        assertEquals(true, refreshed)
    }

    @Test
    fun primaryActionIsLabelledRefreshNotBuy() {
        setScreen()

        compose.onNodeWithText("Обновить").assertIsDisplayed()
    }

    @Test
    fun switchTelegramReportsIntent() {
        var switched = false
        setScreen(onSwitchTelegram = { switched = true })

        compose.onNodeWithTag(NO_SUBSCRIPTION_SWITCH_TAG).performClick()

        assertEquals(true, switched)
    }
}
