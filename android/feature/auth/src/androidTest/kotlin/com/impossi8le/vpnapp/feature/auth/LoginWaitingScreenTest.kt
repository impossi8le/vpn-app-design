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
        prefilledNonce: String? = null,
        onPrefilledNonceConsumed: () -> Unit = {},
    ) {
        compose.setContent {
            LoginWaitingScreen(
                signingIn = signingIn,
                errorText = errorText,
                onSubmitNonce = onSubmitNonce,
                onReopenTelegram = {},
                prefilledNonce = prefilledNonce,
                onPrefilledNonceConsumed = onPrefilledNonceConsumed,
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

        // Цифры отсчёта живут в отдельном `Text` со своим тегом — по нему видно,
        // что таймер отрисован, а не спрятан в общей фразе.
        //
        // `assertExists`, а НЕ `assertIsDisplayed`: на эмуляторе в CI экран
        // 320x640, строка отсчёта уходит ниже сгиба, и «не отображается» здесь
        // значит «не поместилась», а не «не отрисована». Проверка видимости
        // падала бы из-за размера экрана, а не из-за поведения.
        compose.onNodeWithTag(LOGIN_WAITING_COUNTDOWN_TAG).assertExists()
    }

    @Test
    fun prefilledNonceFromLinkEndsUpInTheFieldAndSubmits() {
        // Ради этого шага задача и делалась: код из ссылки избавляет от набора
        // шести цифр руками. Проверяем, что он реально доходит до поля — то есть
        // что нажатие отдаёт наружу именно его, без участия клавиатуры.
        var submitted = ""
        setScreen(prefilledNonce = "482109", onSubmitNonce = { submitted = it })

        compose.onNodeWithTag(LOGIN_WAITING_SUBMIT_TAG).performClick()

        assertEquals("482109", submitted)
    }

    @Test
    fun prefilledNonceIsReportedConsumedOnce() {
        // Значение подставляется один раз и сразу помечается использованным:
        // иначе повторная композиция затирала бы правку пользователя.
        var consumed = 0
        setScreen(prefilledNonce = "482109", onPrefilledNonceConsumed = { consumed++ })

        compose.waitForIdle()

        assertEquals(1, consumed)
    }

    @Test
    fun noNonceMeansNothingIsConsumed() {
        // Обычный путь — ручной ввод: подставлять нечего, и дёргать колбэк незачем.
        var consumed = 0
        setScreen(onPrefilledNonceConsumed = { consumed++ })

        compose.waitForIdle()

        assertEquals(0, consumed)
    }
}
