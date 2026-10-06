package com.impossi8le.vpnapp.feature.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Поле кода из бота как его видит пользователь.
 *
 * ИМЕНА МЕТОДОВ БЕЗ ПРОБЕЛОВ И ОБРАТНЫХ КАВЫЧЕК: инструментальные тесты идут в
 * DEX, а D8 запрещает пробелы в именах методов. На JVM обратные кавычки
 * работают, здесь ломают сборку.
 *
 * Проверяется то, ради чего этот шаг и существует: ручной ввод кода — это
 * защита входа от подмены (login-CSRF), и он обязателен. Если поле не отдаёт
 * введённое наружу, вход не завершится никогда.
 */
class LoginWaitingScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(
        signingIn: Boolean = false,
        errorText: String? = null,
        onSubmitNonce: (String) -> Unit = {},
    ) {
        compose.setContent {
            LoginWaitingScreen(
                signingIn = signingIn,
                errorText = errorText,
                onSubmitNonce = onSubmitNonce,
                onReopenTelegram = {},
                onContinueDemo = {},
            )
        }
    }

    @Test
    fun nonceFieldIsShownAndSubmits() {
        var submitted = ""
        setScreen(onSubmitNonce = { submitted = it })

        compose.onNodeWithTag(LOGIN_WAITING_NONCE_TAG).performTextInput("4821")
        compose.onNodeWithTag(LOGIN_WAITING_SUBMIT_TAG).performClick()

        assertEquals("4821", submitted)
    }

    @Test
    fun nonDigitsAreStrippedAndCodeIsCappedAtSix() {
        var submitted = ""
        setScreen(onSubmitNonce = { submitted = it })

        // Буквы и лишние цифры: поле держит только цифры и не длиннее шести.
        compose.onNodeWithTag(LOGIN_WAITING_NONCE_TAG).performTextInput("4a8b21099")
        compose.onNodeWithTag(LOGIN_WAITING_SUBMIT_TAG).performClick()

        assertEquals("482109", submitted)
    }

    @Test
    fun errorTextIsShownWhenLoginFails() {
        setScreen(errorText = "введите код из Telegram")

        compose.onNodeWithText("введите код из Telegram").assertIsDisplayed()
    }

    @Test
    fun countdownDigitsAreShown() {
        setScreen()

        // Цифры отсчёта живут в отдельном `Text` со своим тегом — именно по нему
        // видно, что таймер отрисован и обновляем, а не спрятан в общей фразе.
        // Начальное значение — полный срок (5:00); дальше оно тикает само.
        compose.onNodeWithTag(LOGIN_WAITING_COUNTDOWN_TAG).assertIsDisplayed()
    }
}
