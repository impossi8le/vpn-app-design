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
 * Экран «Доступ к стране закрыт» как его видит пользователь.
 *
 * Имена методов БЕЗ пробелов и обратных кавычек: эти тесты компилируются в DEX,
 * а D8 до версии 040 запрещает пробелы в именах методов.
 *
 * Тест держит ключевую фразу «Это не сбой сети». Раньше отзыв выглядел ровно
 * как «сервер не отвечает», и человек бесконечно повторял попытку, которая не
 * могла сработать.
 */
class AccessRevokedScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(
        onContactSupport: () -> Unit = {},
        onRefreshAccess: () -> Unit = {},
        onSwitchTelegram: () -> Unit = {},
    ) {
        compose.setContent {
            AccessRevokedScreen(
                onContactSupport = onContactSupport,
                onRefreshAccess = onRefreshAccess,
                onSwitchTelegram = onSwitchTelegram,
            )
        }
    }

    @Test
    fun screenSaysTheCountryIsClosedRatherThanTheNetworkFailing() {
        // Без этой оговорки отзыв читается как временная сетевая неудача, и
        // пользователь повторяет бесполезную попытку вместо обращения в поддержку.
        setScreen()

        compose.onNodeWithTag(ACCESS_REVOKED_TITLE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Доступ к стране закрыт").assertIsDisplayed()
        compose.onNodeWithText("Это не сбой сети.", substring = true).assertIsDisplayed()
    }

    @Test
    fun allThreeWaysOutAreOffered() {
        // Путь один — поддержка или обновление данных, а не «подключиться ещё
        // раз». Поэтому на экране нет «Повторить подключение».
        setScreen()

        compose.onNodeWithTag(ACCESS_REVOKED_SUPPORT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ACCESS_REVOKED_REFRESH_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ACCESS_REVOKED_SWITCH_TAG).assertIsDisplayed()
    }

    @Test
    fun supportReportsIntent() {
        var contacted = false
        setScreen(onContactSupport = { contacted = true })

        compose.onNodeWithTag(ACCESS_REVOKED_SUPPORT_TAG).performClick()

        assertEquals(true, contacted)
    }

    @Test
    fun refreshAccessReportsIntent() {
        var refreshed = false
        setScreen(onRefreshAccess = { refreshed = true })

        compose.onNodeWithTag(ACCESS_REVOKED_REFRESH_TAG).performClick()

        assertEquals(true, refreshed)
    }

    @Test
    fun switchTelegramReportsIntent() {
        var switched = false
        setScreen(onSwitchTelegram = { switched = true })

        compose.onNodeWithTag(ACCESS_REVOKED_SWITCH_TAG).performClick()

        assertEquals(true, switched)
    }

    @Test
    fun screenDoesNotOfferAReconnectAttempt() {
        // «Подключиться снова» здесь — тупик: отзыв это решение сервера, оно
        // само не вернётся.
        setScreen()

        listOf("Подключить", "Повторить").forEach { word ->
            compose.onNodeWithText(word, substring = true).assertDoesNotExist()
        }
    }
}
