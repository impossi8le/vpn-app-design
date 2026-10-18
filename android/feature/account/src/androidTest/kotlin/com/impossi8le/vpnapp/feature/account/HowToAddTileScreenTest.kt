package com.impossi8le.vpnapp.feature.account

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
 * Экран «Как добавить плитку» как его видит пользователь.
 *
 * Имена методов БЕЗ пробелов и обратных кавычек: эти тесты компилируются в DEX,
 * а D8 до версии 040 запрещает пробелы в именах методов.
 *
 * Главное, что экран обязан сказать, — плитка НЕ появилась сама. Иначе
 * инструкцию прочтут как «уже добавлено» и будут искать в шторке то, чего там нет.
 */
class HowToAddTileScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(onBack: () -> Unit = {}) {
        compose.setContent { HowToAddTileScreen(onBack = onBack) }
    }

    @Test
    fun introSaysTheIconHasToBeAddedByHand() {
        setScreen()

        compose
            .onNodeWithText("Значок не появляется на панели сам", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun allStepsAreVisible() {
        // Каждый шаг — отдельная строка: инструкция читается и выполняется
        // пошагово, а слитый абзац на телефоне не выполним.
        setScreen()

        TILE_HOW_TO_STEPS.forEach { step ->
            compose.onNodeWithText(step.title).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun stepsDoNotClaimAnExactMenuItemName() {
        // Названия и расположение пунктов отличаются между прошивками: дословное
        // обещание соврало бы на части устройств.
        setScreen()

        compose
            .onNodeWithText("может немного отличаться", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun backReportsIntent() {
        var back = false
        setScreen(onBack = { back = true })

        compose.onNodeWithTag(TILE_HOW_TO_BACK_TAG).performClick()

        assertEquals(true, back)
    }

    @Test
    fun screenPromisesNothingAboutProtection() {
        // Плитка говорит только о состоянии туннеля; подсказка о ней — про
        // удобство, и слов про защиту здесь быть не должно (§6).
        setScreen()

        listOf("защит", "безопасн", "анонимн", "шифров").forEach { word ->
            compose
                .onNodeWithText(word, substring = true, ignoreCase = true)
                .assertDoesNotExist()
        }
    }
}
