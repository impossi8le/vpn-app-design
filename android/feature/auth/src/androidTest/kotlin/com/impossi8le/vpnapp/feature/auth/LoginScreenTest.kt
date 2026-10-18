package com.impossi8le.vpnapp.feature.auth

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Первый экран входа как его видит пользователь.
 *
 * ИМЕНА МЕТОДОВ БЕЗ ПРОБЕЛОВ И ОБРАТНЫХ КАВЫЧЕК: инструментальные тесты идут в
 * DEX, а D8 запрещает пробелы в именах методов. На JVM обратные кавычки
 * работают, здесь ломают сборку.
 *
 * Экран логики не содержит — он только показывает текст и отдаёт наружу три
 * решения: вход, политику и условия. Поэтому и проверять здесь нечего, кроме
 * того, что до пользователя доходит нужное и что каждая ссылка ведёт туда, куда
 * подписана. Отдельно закрепляется то, ради чего этот экран написан именно так:
 * он называет кнопку бота входа («Подтвердить вход») и раскрывает, что видит
 * оператор, — до кнопки входа, а не спрятав это в политику.
 *
 * ВАЖНО про «не хардкодить статус»: экран входа не имеет дела с состоянием
 * туннеля и не должен обещать защиту. Системный флаг VPN защиту не доказывает,
 * и здесь это закреплено [screenMakesNoProtectionClaim] — статус сюда не протекает.
 */
class LoginScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(
        onLogin: () -> Unit = {},
        onPrivacyPolicy: () -> Unit = {},
        onTerms: () -> Unit = {},
    ) {
        compose.setContent {
            LoginScreen(
                onLogin = onLogin,
                onPrivacyPolicy = onPrivacyPolicy,
                onTerms = onTerms,
            )
        }
    }

    @Test
    fun loginButtonStartsLogin() {
        var logins = 0
        setScreen(onLogin = { logins++ })

        compose.onNodeWithTag(LOGIN_BUTTON_TAG).performClick()

        // Ровно один вызов: двойной запуск входа открыл бы второго бота и
        // потерял бы первый челлендж.
        assertEquals(1, logins)
    }

    @Test
    fun privacyLinkOpensOnlyPrivacy() {
        var privacy = 0
        var terms = 0
        setScreen(onPrivacyPolicy = { privacy++ }, onTerms = { terms++ })

        // Прокрутка обязательна: на низком разрешении (эмулятор API 30) строка
        // ссылок уходит за границу экрана, и клик без неё не доходит — тест
        // падал именно так, поймав реальный дефект (экран не прокручивался).
        compose.onNodeWithTag(LOGIN_PRIVACY_TAG).performScrollTo().performClick()

        // Вторая ссылка не сработала: обе ссылки в одной строке, и копипаст
        // одного `clickable` на обе отправлял бы пользователя не туда.
        assertEquals(1, privacy)
        assertEquals(0, terms)
    }

    @Test
    fun termsLinkOpensOnlyTerms() {
        var privacy = 0
        var terms = 0
        setScreen(onPrivacyPolicy = { privacy++ }, onTerms = { terms++ })

        compose.onNodeWithTag(LOGIN_TERMS_TAG).performScrollTo().performClick()

        assertEquals(1, terms)
        assertEquals(0, privacy)
    }

    @Test
    fun titleIsShown() {
        setScreen()

        // Заголовок — первое, что видит пользователь при запуске; его наличие
        // заодно доказывает, что экран вообще отрисовался до кнопки.
        compose.onNodeWithTag(LOGIN_TITLE_TAG).assertIsDisplayed()
    }

    @Test
    fun captionNamesTheLoginBotConfirmButton() {
        setScreen()

        // Это прямое следствие дефекта «кнопка уводила не в того бота»: экран
        // должен обещать кнопку «Подтвердить вход». Её присылает бот ВХОДА;
        // в боте поддержки такой кнопки нет, и обещание стало бы тупиком.
        compose.onNodeWithText("Подтвердить вход", substring = true).assertIsDisplayed()
    }

    @Test
    fun disclosureNamesWhatTheOperatorSees() {
        setScreen()

        // Раскрытие стоит на пути входа, а не спрятано в политику: пользователь
        // должен узнать, что оператор видит Telegram ID, пока решает, входить ли.
        compose.onNodeWithText("Telegram ID", substring = true).assertExists()
        compose.onNodeWithText("список ваших подключений", substring = true).assertExists()
    }

    @Test
    fun allThreeStepsAreShown() {
        setScreen()

        // Три шага объясняют, что покупка в боте и вход в приложении — один и тот
        // же Telegram. Пропущенный шаг ломает эту связь для нового пользователя.
        compose.onNodeWithText("Подписка в боте").assertExists()
        compose.onNodeWithText("Вход тем же Telegram", substring = true).assertExists()
        compose.onNodeWithText("Подключение в один тап").assertExists()
    }

    @Test
    fun screenMakesNoProtectionClaim() {
        setScreen()

        // Экран входа ничего не знает о туннеле. Появление здесь «защищено» или
        // «подключено» означало бы статус, выведенный не из замера, — то, что
        // проект запрещает. Проверяем, что такого текста тут нет.
        compose.onNodeWithText("защищено", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("подключено", substring = true, ignoreCase = true).assertDoesNotExist()
    }
}
