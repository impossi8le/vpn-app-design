package com.impossi8le.vpnapp.feature.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.impossi8le.vpnapp.core.ui.UpdateUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Ээкран принудительного обновления как его видит пользователь.
 *
 * Имена методов БЕЗ пробелов и обратных кавычек: эти тесты компилируются в DEX,
 * а D8 до версии 040 запрещает пробелы в именах методов.
 *
 * Экран блокирующий: он показывается ВМЕСТО рабочего, а не поверх него. Значит
 * на нём обязан оставаться выход — повторная попытка не должна требовать
 * перезапуска приложения.
 */
class ForceUpdateScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(
        installedVersionName: String = "1.0.5",
        update: UpdateUiState.Available = UpdateUiState.Available(versionCode = 42, required = true),
        updateProgress: Int? = null,
        updateMessage: String? = null,
        onDownloadUpdate: () -> Unit = {},
        onCheckUpdate: () -> Unit = {},
    ) {
        compose.setContent {
            ForceUpdateScreen(
                installedVersionName = installedVersionName,
                update = update,
                updateProgress = updateProgress,
                updateMessage = updateMessage,
                onDownloadUpdate = onDownloadUpdate,
                onCheckUpdate = onCheckUpdate,
            )
        }
    }

    @Test
    fun screenSaysWhyTheAppIsLocked() {
        setScreen()

        compose.onNodeWithText("Нужно обновить приложение").assertIsDisplayed()
        compose
            .onNodeWithText("Эта версия больше не поддерживается", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun bothInstalledAndRequiredVersionsAreNamed() {
        // Пользователю видно, насколько он отстал: без «Требуется» непонятно,
        // что именно нужно поставить.
        setScreen(installedVersionName = "1.0.5")

        compose.onNodeWithText("1.0.5").assertIsDisplayed()
        compose.onNodeWithText("1.0.42").assertIsDisplayed()
    }

    @Test
    fun updateButtonReportsIntent() {
        var downloaded = false
        setScreen(onDownloadUpdate = { downloaded = true })

        compose.onNodeWithTag(FORCE_UPDATE_TAG).performClick()

        assertEquals(true, downloaded)
    }

    @Test
    fun buttonIsLabelledUpdateWhileNothingIsHappening() {
        setScreen()

        compose.onNodeWithText("Обновить").assertIsDisplayed()
    }

    @Test
    fun progressIsShownAndButtonStopsClaimingToBeIdle() {
        // Пока идёт закачка, кнопка не должна звать обновлять снова.
        setScreen(updateProgress = 60)

        compose.onNodeWithText("60%").assertIsDisplayed()
        compose.onNodeWithText("Скачивание…").assertIsDisplayed()
        compose.onNodeWithText("Обновить").assertDoesNotExist()
    }

    @Test
    fun failureReasonIsShownAlongsideTheRetryNotInsteadOfIt() {
        // Причина рядом с кнопкой, а не вместо неё: иначе после отказа повторить
        // попытку можно было бы только перезапуском приложения.
        setScreen(updateMessage = "Не удалось скачать обновление")

        // Текст причины и сама кнопка видны одновременно. Тег FORCE_UPDATE_TAG
        // намеренно не используется: он есть и у кнопки, и у плашки, и запрос по
        // нему вернул бы две ноды вместо одной.
        compose.onNodeWithText("Не удалось скачать обновление").assertIsDisplayed()
        compose.onNodeWithText("Обновить").assertIsDisplayed()
    }

    @Test
    fun recheckLinkExistsForUsersWhoUpdatedAnotherWay() {
        // Обновиться можно было и через бота: экран не должен требовать
        // перезапуска, чтобы заметить это.
        var rechecked = false
        setScreen(onCheckUpdate = { rechecked = true })

        compose.onNodeWithTag(FORCE_UPDATE_RECHECK_TAG).performClick()

        assertEquals(true, rechecked)
        compose.onNodeWithText("Я обновился — проверить снова ›").assertIsDisplayed()
    }

    @Test
    fun screenDoesNotInventAReasonForTheUpdate() {
        // Причины (закрытая дыра, сменившийся протокол) не выдумываются: сказано
        // ровно то, что известно.
        setScreen()

        listOf("дыр", "уязвим", "взлом", "атак").forEach { word ->
            compose.onNodeWithText(word, substring = true, ignoreCase = true).assertDoesNotExist()
        }
    }
}
