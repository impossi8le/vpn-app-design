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
 * Экран «Как включить VPN» как его видит пользователь.
 *
 * Имена методов БЕЗ пробелов и обратных кавычек: эти тесты компилируются в DEX,
 * а D8 до версии 040 запрещает пробелы в именах методов.
 *
 * Своего экрана вместо скрытого перехода в системные настройки: схема `App-Prefs`
 * ломается между версиями, поэтому здесь статичная инструкция и кнопка, которая
 * просто перечитывает состояние разрешения.
 */
class HowToEnableVpnScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(
        onBack: () -> Unit = {},
        onRecheck: () -> Unit = {},
    ) {
        compose.setContent { HowToEnableVpnScreen(onBack = onBack, onRecheck = onRecheck) }
    }

    @Test
    fun titleNamesThePurposeOfTheScreen() {
        setScreen()

        compose.onNodeWithTag(HOW_TO_TITLE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Как включить VPN").assertIsDisplayed()
    }

    @Test
    fun screenExplainsThatTheSystemPromptIsShownOnlyOnce() {
        // Если этого не сказать, отказ читается как временная неудача, и
        // пользователь ждёт повторного запроса, которого не будет.
        //
        // Без performScrollTo: этот экран — обычный Column, а не прокручиваемый
        // список, и прокрутки у него нет вовсе. Содержимое целиком помещается на
        // экране, поэтому проверка видимости честная.
        setScreen()

        compose
            .onNodeWithText("показывает только один раз", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun allFourStepsAreVisible() {
        // Все четыре шага на экране одновременно: инструкция выполняется по
        // памяти, и шаг за сгибом — это шаг, который не выполнят.
        setScreen()

        listOf(
            "1. Откройте Настройки",
            "2. Раздел",
            "3. Строка VPN",
            "4. Включите переключатель",
        ).forEach { step ->
            compose.onNodeWithText(step).assertIsDisplayed()
        }
    }

    @Test
    fun stepsSayWhereToLookRatherThanPromisingExactWording() {
        setScreen()

        compose.onNodeWithText("«VPN и управление устройством»").assertIsDisplayed()
    }

    @Test
    fun recheckReadsCurrentPermissionInsteadOfNavigatingAway() {
        // Кнопка не уводит в системные настройки: она перечитывает состояние,
        // чтобы пользователь не потерялся между приложением и настройками.
        var rechecked = false
        setScreen(onRecheck = { rechecked = true })

        compose.onNodeWithTag(HOW_TO_RECHECK_TAG).performClick()

        assertEquals(true, rechecked)
        compose.onNodeWithText("Проверить снова").assertIsDisplayed()
    }

    @Test
    fun backReportsIntent() {
        var back = false
        setScreen(onBack = { back = true })

        compose.onNodeWithTag(HOW_TO_BACK_TAG).performClick()

        assertEquals(true, back)
    }

    @Test
    fun missingPermissionIsNotFramedAsTheUsersFault() {
        // Нет разрешения — известный путь, который надо пройти ногами по шагам,
        // а не ошибка пользователя.
        setScreen()

        listOf("ошибк", "неверно", "неправильно", "виноват").forEach { word ->
            compose
                .onNodeWithText(word, substring = true, ignoreCase = true)
                .assertDoesNotExist()
        }
    }
}
