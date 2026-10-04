package com.impossi8le.vpnapp.feature.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionVerdict
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Экран подключения как его видит пользователь.
 *
 * ИМЕНА МЕТОДОВ БЕЗ ПРОБЕЛОВ И ОБРАТНЫХ КАВЫЧЕК: инструментальные тесты идут в
 * DEX, а D8 до версии 040 запрещает пробелы в именах методов. На JVM обратные
 * кавычки работают, здесь ломают сборку.
 *
 * На JVM уже проверено, что `presentation()` выбирает правильный текст и цвет.
 * Здесь проверяется то, чего на JVM не видно: что решение доходит до экрана.
 * Тест падает, если кто-то захардкодит зелёный заголовок в разметке мимо
 * `presentation()` — это и был дефект, найденный на UX-ревью.
 *
 * Отдельно проверяется блок «Защита»: он отвечает на вопрос «а оно правда
 * работает?», и именно в нём макет разместил честность — заголовок может
 * говорить «идёт проверка», а блок рядом обязан говорить «не проверено».
 */
class ConnectionScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun protected() = ConnectionStatus.Protected(
        (ProtectionVerdict.evaluate(true, true, true) as ProtectionVerdict.Confirmed).evidence,
    )

    private fun setScreen(
        status: ConnectionStatus,
        onAction: (StatusAction) -> Unit = {},
        configs: List<ConfigRowState> = emptyList(),
    ) {
        compose.setContent {
            ConnectionScreen(
                status = status,
                configs = configs,
                onAction = onAction,
                onOpenAccount = {},
                onRefreshConfigs = {},
                onSelectConfig = {},
                onSwitchCountry = {},
            )
        }
    }

    @Test
    fun raisedTunnelShowsNotVerifiedRatherThanProtected() {
        setScreen(ConnectionStatus.VerifyingProtection)

        // Главный дефект UX-ревью: зелёный заголовок при неподтверждённой защите.
        compose.onNodeWithTag(CONNECTION_TITLE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Туннель поднят").assertIsDisplayed()
    }

    @Test
    fun verifyingStateSaysProtectionNotVerifiedInTheProtectionBlock() {
        setScreen(ConnectionStatus.VerifyingProtection)

        // Блок «Защита» показывает «проверяем», а не «проверено»: поднятый
        // интерфейс не доказывает, что трафик идёт через него.
        compose.onNodeWithTag(CONNECTION_PROTECTION_STATUS_TAG).assertIsDisplayed()
        compose.onNodeWithText("проверяем…").assertIsDisplayed()
    }

    @Test
    fun confirmedProtectionShowsProtected() {
        setScreen(protected())

        compose.onNodeWithText("Подключено и защищено").assertIsDisplayed()
    }

    @Test
    fun confirmedProtectionMarksTheProtectionBlockAsVerified() {
        setScreen(protected())

        compose.onNodeWithText("проверено").assertIsDisplayed()
        // IPv6 и DNS показываются как подтверждённые факты замера.
        compose.onNodeWithText("закрыт").assertIsDisplayed()
    }

    @Test
    fun probeFailureExplainsThereIsNoProtection() {
        setScreen(
            ConnectionStatus.ProtectionFailed(
                ProtectionVerdict.evaluate(false, true, true) as ProtectionVerdict.Failed,
            ),
        )

        compose.onNodeWithText("Трафик не идёт через туннель").assertIsDisplayed()
        compose.onNodeWithText("не пройдено").assertIsDisplayed()
    }

    @Test
    fun disconnectedStateIsNeutralAndOffersConnect() {
        setScreen(ConnectionStatus.Disconnected)

        compose.onNodeWithText("Не подключено").assertIsDisplayed()
        compose.onNodeWithTag(CONNECTION_ACTION_TAG).assertIsDisplayed()
        compose.onNodeWithText("Подключить").assertIsDisplayed()
    }

    @Test
    fun connectButtonReportsConnectIntent() {
        var received: StatusAction? = null
        setScreen(ConnectionStatus.Disconnected, onAction = { received = it })

        compose.onNodeWithTag(CONNECTION_ACTION_TAG).performClick()

        assertEquals(StatusAction.Connect, received)
    }

    @Test
    fun connectingStateOffersCancelNotDisconnect() {
        var received: StatusAction? = null
        setScreen(ConnectionStatus.Connecting, onAction = { received = it })

        // Матрица кнопки из макета: в состоянии «подключение» предлагается
        // «Отменить». Прежняя версия предлагала «Отключить» — то есть отключить
        // то, чего ещё нет.
        compose.onNodeWithText("Отменить").assertIsDisplayed()
        compose.onNodeWithTag(CONNECTION_ACTION_TAG).performClick()

        assertEquals(StatusAction.Cancel, received)
    }

    @Test
    fun expiredConfigIsShownAsExpiredAndNotSelectable() {
        var selected: String? = null
        compose.setContent {
            ConnectionScreen(
                status = ConnectionStatus.Disconnected,
                configs = listOf(
                    ConfigRowState(
                        id = "nl-dead",
                        name = "Нидерланды",
                        countryCode = "NL",
                        subtitle = "Истёк 30.09.2026",
                        status = ConfigRowStatus.Expired,
                    ),
                ),
                onAction = {},
                onOpenAccount = {},
                onRefreshConfigs = {},
                onSelectConfig = { selected = it },
                onSwitchCountry = {},
            )
        }

        compose.onNodeWithText("Истёк").assertIsDisplayed()
        compose.onNodeWithTag(configRowTag("nl-dead")).performClick()

        // Истёкшее подключение нельзя выбрать: тап не должен ничего сообщать.
        assertEquals(null, selected)
    }

    // Тест «активное подключение отмечено в списке» здесь СОЗНАТЕЛЬНО не написан.
    //
    // Проверять его через этот экран нельзя: строки лежат в LazyColumn, и на
    // экране 320x640 вторая строка за сгибом — LazyColumn её просто не
    // составляет, поэтому ни `assertIsDisplayed`, ни `assertExists` её не
    // находят. Тест падал бы из-за размера экрана, а не из-за поведения.
    //
    // Само поведение — что активное подключение получает статус Active, а
    // истёкшее Expired — проверено на JVM в ConfigMappingTest, где нет ни
    // разметки, ни прокрутки.

}