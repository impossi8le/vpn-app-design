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
 * Экран аккаунта как его видит пользователь.
 *
 * Имена методов БЕЗ пробелов и обратных кавычек: эти тесты компилируются в DEX,
 * а D8 до версии 040 запрещает пробелы в именах методов.
 *
 * Проверяются решения, о которых не видно ничего на JVM: что номер показывается
 * по кнопке, а не сразу (скриншот в поддержку не должен отдавать профиль), и что
 * прочерк не маскируется под «есть, но скрыто».
 */
class AccountScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun state(
        telegramId: String? = "123456789",
        telegramIdRevealed: Boolean = false,
        confirmCountrySwitch: Boolean = false,
        activeConnections: Int = 2,
        totalConnections: Int = 3,
        expiredConnections: Int = 1,
        subscriptionUntil: String = "12.11.2026",
    ) = AccountScreenState(
        telegramId = telegramId,
        telegramIdRevealed = telegramIdRevealed,
        activeConnections = activeConnections,
        totalConnections = totalConnections,
        expiredConnections = expiredConnections,
        subscriptionUntil = subscriptionUntil,
        confirmCountrySwitch = confirmCountrySwitch,
    )

    private fun setScreen(
        state: AccountScreenState = state(),
        appVersion: String = "1.2.3",
        onBack: () -> Unit = {},
        onToggleTelegramId: () -> Unit = {},
        onConfirmCountrySwitchChange: (Boolean) -> Unit = {},
        onSupportChat: () -> Unit = {},
        onSendDiagnostics: () -> Unit = {},
        onOpenAbout: () -> Unit = {},
        onDeleteAccount: () -> Unit = {},
        onSignOut: () -> Unit = {},
    ) {
        compose.setContent {
            AccountScreen(
                state = state,
                appVersion = appVersion,
                onBack = onBack,
                onToggleTelegramId = onToggleTelegramId,
                onConfirmCountrySwitchChange = onConfirmCountrySwitchChange,
                onSupportChat = onSupportChat,
                onSendDiagnostics = onSendDiagnostics,
                onOpenAbout = onOpenAbout,
                onDeleteAccount = onDeleteAccount,
                onSignOut = onSignOut,
            )
        }
    }

    @Test
    fun telegramIdIsMaskedUntilUserAsksToRevealIt() {
        // Номер не показывается сразу: экран идёт в поддержку скриншотом, и
        // профиль аккаунта не должен уезжать туда сам.
        setScreen(state = state(telegramId = "123456789", telegramIdRevealed = false))

        compose.onNodeWithText("••••••••").assertIsDisplayed()
        compose.onNodeWithText("123456789").assertDoesNotExist()
    }

    @Test
    fun revealedTelegramIdShowsTheValue() {
        setScreen(state = state(telegramId = "123456789", telegramIdRevealed = true))

        compose.onNodeWithText("123456789").assertIsDisplayed()
        compose.onNodeWithText("••••••••").assertDoesNotExist()
    }

    @Test
    fun revealButtonTogglesToHideOnceRevealed() {
        // Подпись меняется вместе с состоянием, иначе пользователь не поймёт,
        // как вернуть маску обратно.
        setScreen(state = state(telegramIdRevealed = true))

        compose.onNodeWithTag(ACCOUNT_REVEAL_ID_TAG).assertIsDisplayed()
        compose.onNodeWithText("Скрыть").assertIsDisplayed()
    }

    @Test
    fun unknownTelegramIdIsDashWithoutRevealButton() {
        // Маска на отсутствующем значении читалась бы как «есть, но скрыто»:
        // пользователь нажал бы «Показать» и получил пустоту.
        setScreen(state = state(telegramId = null))

        compose.onNodeWithText("—").assertIsDisplayed()
        compose.onNodeWithTag(ACCOUNT_REVEAL_ID_TAG).assertDoesNotExist()
    }

    @Test
    fun revealButtonReportsIntent() {
        var toggled = false
        setScreen(onToggleTelegramId = { toggled = true })

        compose.onNodeWithTag(ACCOUNT_REVEAL_ID_TAG).performClick()

        assertEquals(true, toggled)
    }

    @Test
    fun connectionCountsComeFromStateRatherThanBeingHardcoded() {
        setScreen(state = state(activeConnections = 2, totalConnections = 3, expiredConnections = 4))

        compose.onNodeWithText("2 из 3").assertIsDisplayed()
        compose.onNodeWithText("4 истекли").assertIsDisplayed()
    }

    @Test
    fun subscriptionEndDateIsShownWithItsPreposition() {
        setScreen(state = state(subscriptionUntil = "12.11.2026"))

        compose.onNodeWithText("до 12.11.2026").assertIsDisplayed()
    }

    @Test
    fun confirmSwitchReportsTheNewValue() {
        var requested: Boolean? = null
        setScreen(state = state(confirmCountrySwitch = false), onConfirmCountrySwitchChange = { requested = it })

        compose.onNodeWithTag(ACCOUNT_CONFIRM_SWITCH_TAG).performScrollTo().performClick()

        assertEquals(true, requested)
    }

    @Test
    fun supportChatReportsIntent() {
        var opened = false
        setScreen(onSupportChat = { opened = true })

        compose.onNodeWithTag(ACCOUNT_SUPPORT_CHAT_TAG).performScrollTo().performClick()

        assertEquals(true, opened)
    }

    @Test
    fun diagnosticsReportSaysItCarriesNoPersonalData() {
        // Подпись под кнопкой — часть обещания: без неё «отправить отчёт»
        // читалось бы как отправка профиля.
        setScreen()

        compose.onNodeWithTag(ACCOUNT_SEND_DIAGNOSTICS_TAG).performScrollTo().assertIsDisplayed()
        compose
            .onNodeWithText("Отчёт без личных данных", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun sendDiagnosticsReportsIntent() {
        var sent = false
        setScreen(onSendDiagnostics = { sent = true })

        compose.onNodeWithTag(ACCOUNT_SEND_DIAGNOSTICS_TAG).performScrollTo().performClick()

        assertEquals(true, sent)
    }

    @Test
    fun aboutOpensServicePage() {
        var opened = false
        setScreen(onOpenAbout = { opened = true })

        compose.onNodeWithTag(ACCOUNT_ABOUT_TAG).performScrollTo().performClick()

        assertEquals(true, opened)
    }

    @Test
    fun backReportsIntent() {
        var back = false
        setScreen(onBack = { back = true })

        compose.onNodeWithTag(ACCOUNT_BACK_TAG).performClick()

        assertEquals(true, back)
    }

    @Test
    fun irreversibleActionsSitUnderTheirOwnHeading() {
        // Отдельный разделитель отделяет стирание аккаунта от настроек: без него
        // удаление стояло бы в одном ряду с тумблером.
        setScreen()

        // Подпись раздела рисуется в верхнем регистре (`SectionLabel`), поэтому
        // ищем без учёта регистра: тест держит смысл, а не типографский приём.
        compose
            .onNodeWithText("Опасное действие", ignoreCase = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag(ACCOUNT_DELETE_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun deleteAccountReportsIntent() {
        var deleted = false
        setScreen(onDeleteAccount = { deleted = true })

        compose.onNodeWithTag(ACCOUNT_DELETE_TAG).performScrollTo().performClick()

        assertEquals(true, deleted)
    }

    @Test
    fun signOutReportsIntent() {
        // Выход — нейтральная кнопка, а не красная: он не опасен, в отличие от
        // удаления, и красить их одинаково значило бы пугать зря.
        var signedOut = false
        setScreen(onSignOut = { signedOut = true })

        compose.onNodeWithTag(ACCOUNT_LOGOUT_TAG).performScrollTo().performClick()

        assertEquals(true, signedOut)
    }
}
