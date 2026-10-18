package com.impossi8le.vpnapp.feature.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.impossi8le.vpnapp.core.ui.UpdateUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Экран «О сервисе» как его видит пользователь.
 *
 * Имена методов БЕЗ пробелов и обратных кавычек: эти тесты компилируются в DEX,
 * а D8 до версии 040 запрещает пробелы в именах методов.
 *
 * Главное здесь — раздел обновления. Он уже один раз терял путь к установке:
 * после успешной закачки строка возвращалась к «Скачать», и скачанный файл
 * становился недостижим. Тест держит оба конца этого пути.
 */
class AboutScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(
        appVersion: String = "1.2.3",
        buildSha: String = "abc1234",
        update: UpdateUiState = UpdateUiState.Idle,
        updateProgress: Int? = null,
        updateMessage: String? = null,
        updateReady: Boolean = false,
        onCheckUpdate: () -> Unit = {},
        onDownloadUpdate: () -> Unit = {},
        onInstallUpdate: () -> Unit = {},
        onBack: () -> Unit = {},
        onOpenPrivacy: () -> Unit = {},
        onOpenTerms: () -> Unit = {},
        onOpenTileHowTo: () -> Unit = {},
        onOpenLicenses: () -> Unit = {},
    ) {
        compose.setContent {
            AboutScreen(
                appVersion = appVersion,
                buildSha = buildSha,
                update = update,
                updateProgress = updateProgress,
                updateMessage = updateMessage,
                onCheckUpdate = onCheckUpdate,
                onDownloadUpdate = onDownloadUpdate,
                onInstallUpdate = onInstallUpdate,
                updateReady = updateReady,
                onBack = onBack,
                onOpenPrivacy = onOpenPrivacy,
                onOpenTerms = onOpenTerms,
                onOpenTileHowTo = onOpenTileHowTo,
                onOpenLicenses = onOpenLicenses,
            )
        }
    }

    @Test
    fun versionAndBuildAreBothShown() {
        // Сборка git рядом с версией — иначе по «1.2.3» не отличить два разных
        // билда при разборе обращения в поддержку.
        setScreen(appVersion = "1.2.3", buildSha = "abc1234")

        compose.onNodeWithText("1.2.3").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("abc1234").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun idleOffersToCheckForUpdates() {
        setScreen(update = UpdateUiState.Idle)

        compose.onNodeWithTag(ABOUT_CHECK_UPDATE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Проверить ›").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun checkUpdateReportsIntent() {
        var checked = false
        setScreen(update = UpdateUiState.Idle, onCheckUpdate = { checked = true })

        compose.onNodeWithTag(ABOUT_CHECK_UPDATE_TAG).performScrollTo().performClick()

        assertEquals(true, checked)
    }

    @Test
    fun upToDateSaysSoInsteadOfShowingNothing() {
        setScreen(update = UpdateUiState.UpToDate)

        compose.onNodeWithText("установлена последняя").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun failedCheckShowsTheReasonNotUpToDate() {
        // «Версия свежая» при сорвавшейся проверке — это ложный факт: мы не
        // знаем, свежая ли она, и выдать одно за другое нельзя.
        setScreen(update = UpdateUiState.Failed(reason = "нет сети"))

        compose.onNodeWithText("нет сети").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("установлена последняя").assertDoesNotExist()
    }

    @Test
    fun availableUpdateOffersDownloadAndNamesTheVersion() {
        setScreen(update = UpdateUiState.Available(versionCode = 42))

        compose.onNodeWithText("1.0.42").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Скачать ›").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun downloadReportsIntent() {
        var downloaded = false
        setScreen(
            update = UpdateUiState.Available(versionCode = 42),
            onDownloadUpdate = { downloaded = true },
        )

        compose.onNodeWithTag(ABOUT_UPDATE_TAG).performScrollTo().performClick()

        assertEquals(true, downloaded)
    }

    @Test
    fun downloadedUpdateOffersInstallRatherThanDownloadAgain() {
        // Это и был дефект: после успешной закачки строка возвращалась к
        // «Скачать», файл в кеше становился недостижим — обновиться было нечем.
        setScreen(update = UpdateUiState.Available(versionCode = 42), updateReady = true)

        compose.onNodeWithText("Установить ›").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Скачать ›").assertDoesNotExist()
    }

    @Test
    fun installReportsIntent() {
        var installed = false
        setScreen(
            update = UpdateUiState.Available(versionCode = 42),
            updateReady = true,
            onInstallUpdate = { installed = true },
        )

        compose.onNodeWithTag(ABOUT_UPDATE_TAG).performScrollTo().performClick()

        assertEquals(true, installed)
    }

    @Test
    fun progressIsShownAsPercentWhileDownloading() {
        setScreen(
            update = UpdateUiState.Available(versionCode = 42),
            updateProgress = 37,
        )

        compose.onNodeWithText("37%").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun failureMessageDoesNotReplaceTheDownloadAction() {
        // Причина отказа показывается ДОПОЛНИТЕЛЬНО к состоянию, а не вместо
        // него: иначе после отказа система не дала установить APK — строка
        // «Скачать» исчезала и повторить попытку было нечем.
        setScreen(
            update = UpdateUiState.Available(versionCode = 42),
            updateMessage = "Установка отменена",
        )

        compose.onNodeWithText("Установка отменена").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Скачать ›").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tileInfoSaysTheIconIsNotThereYet() {
        // «Плитка уже добавлена» — ложное чтение инструкции: пользователь искал
        // бы на панели то, чего там нет.
        setScreen()

        compose
            .onNodeWithText("Значок нужно добавить на панель самому", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun tileHowToReportsIntent() {
        var opened = false
        setScreen(onOpenTileHowTo = { opened = true })

        compose.onNodeWithTag(ABOUT_TILE_TAG).performScrollTo().performClick()

        assertEquals(true, opened)
    }

    @Test
    fun legalDocumentsAreAllReachable() {
        // Правовые тексты живут в одном разделе «Документы»: пользователь ищет
        // их там же, где политику и условия.
        setScreen()

        compose.onNodeWithTag(ABOUT_PRIVACY_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ABOUT_TERMS_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(ABOUT_LICENSES_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun licensesRowNamesTheEngineAndExplainsWhyItIsListed() {
        // Лицензия ядра известна из наших данных и показывается даже при
        // недоступном ядре — иначе уведомление пропадало бы вместе с библиотекой.
        setScreen()

        compose
            .onNodeWithText("Лицензии сторонних компонентов", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Ядро OpenVPN 3", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun privacyLinkReportsIntent() {
        var opened = false
        setScreen(onOpenPrivacy = { opened = true })

        compose.onNodeWithTag(ABOUT_PRIVACY_TAG).performScrollTo().performClick()

        assertEquals(true, opened)
    }

    @Test
    fun termsLinkReportsIntent() {
        var opened = false
        setScreen(onOpenTerms = { opened = true })

        compose.onNodeWithTag(ABOUT_TERMS_TAG).performScrollTo().performClick()

        assertEquals(true, opened)
    }

    @Test
    fun licensesLinkReportsIntent() {
        var opened = false
        setScreen(onOpenLicenses = { opened = true })

        compose.onNodeWithTag(ABOUT_LICENSES_TAG).performScrollTo().performClick()

        assertEquals(true, opened)
    }

    @Test
    fun backReportsIntent() {
        var back = false
        setScreen(onBack = { back = true })

        compose.onNodeWithTag(ABOUT_BACK_TAG).performClick()

        assertEquals(true, back)
    }

    @Test
    fun pageDoesNotPromiseProtectionOrAnonymity() {
        // Тексты про «безопасность» и «анонимность» из этого экрана вычищены
        // намеренно: без замера это обещание защиты, которой нет.
        setScreen()

        listOf("безопасн", "анонимн", "защищен", "защищён").forEach { word ->
            compose
                .onNodeWithText(word, substring = true, ignoreCase = true)
                .assertDoesNotExist()
        }
    }
}
