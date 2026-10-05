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
 * Экран больше НЕ показывает блок «Защита»: проба защиты — заглушка, и она
 * ничего не измеряла. Проверяется только то, что показывается теперь:
 * «подключено / нет» и имя работающего подключения.
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
        runningConfigName: String? = null,
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
                runningConfigName = runningConfigName,
            )
        }
    }

    @Test
    fun raisedTunnelShowsConnectedNotProtected() {
        setScreen(ConnectionStatus.VerifyingProtection)

        // Главный дефект UX-ревью: зелёный заголовок при неподтверждённой защите.
        // Теперь поднятый туннель — просто «Подключено», без слова о защите.
        compose.onNodeWithTag(CONNECTION_TITLE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Подключено").assertIsDisplayed()
    }

    @Test
    fun raisedTunnelShowsTheRunningConfigName() {
        setScreen(ConnectionStatus.VerifyingProtection, runningConfigName = "Нидерланды")

        compose.onNodeWithText("Подключено").assertIsDisplayed()
        compose.onNodeWithText("Нидерланды").assertIsDisplayed()
    }

    @Test
    fun verifyingProtectionIsNotPresentedAsProtected() {
        setScreen(ConnectionStatus.VerifyingProtection)

        // Ни слова «защищено»: интерфейс поднят, но доказательства нет.
        compose.onNodeWithText("Подключено и защищено").assertDoesNotExist()
        compose.onNodeWithText("защищено").assertDoesNotExist()
    }

    @Test
    fun probeFailureAlsoReadsAsConnected() {
        // Провал пробы тоже значит «туннель поднят»: пользователю показывается
        // подключение, а не текст о несостоявшейся защите.
        setScreen(
            ConnectionStatus.ProtectionFailed(
                ProtectionVerdict.evaluate(false, true, true) as ProtectionVerdict.Failed,
            ),
        )

        compose.onNodeWithText("Подключено").assertIsDisplayed()
        compose.onNodeWithText("Трафик не идёт через туннель").assertDoesNotExist()
    }

    @Test
    fun protectionBlockIsGone() {
        setScreen(protected())

        // Блока «Защита» на экране больше нет ни в одном состоянии.
        compose.onNodeWithText("СОЕДИНЕНИЕ").assertDoesNotExist()
        compose.onNodeWithText("проверено").assertDoesNotExist()
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
