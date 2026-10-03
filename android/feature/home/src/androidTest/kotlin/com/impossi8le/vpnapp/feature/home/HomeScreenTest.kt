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
 * Экран как его видит пользователь.
 *
 * ИМЕНА МЕТОДОВ БЕЗ ПРОБЕЛОВ И ОБРАТНЫХ КАВЫЧЕК: инструментальные тесты идут в
 * DEX, а D8 до версии 040 запрещает пробелы в именах методов. На JVM обратные
 * кавычки работают, здесь ломают сборку.
 *
 * На JVM уже проверено, что `presentation()` выбирает правильный текст и цвет.
 * Здесь проверяется то, чего на JVM не видно: что решение действительно доходит
 * до экрана. Тест падает, если кто-то захардкодит зелёный заголовок в разметке
 * мимо `presentation()` — это и был дефект, найденный на UX-ревью.
 */
class HomeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun protected() = ConnectionStatus.Protected(
        (ProtectionVerdict.evaluate(true, true, true) as ProtectionVerdict.Confirmed).evidence,
    )

    private fun setScreen(status: ConnectionStatus, onConnect: () -> Unit = {}, onDisconnect: () -> Unit = {}) {
        compose.setContent {
            HomeScreen(status = status, onConnect = onConnect, onDisconnect = onDisconnect)
        }
    }

    @Test
    fun raisedTunnelShowsNotVerifiedRatherThanProtected() {
        setScreen(ConnectionStatus.VerifyingProtection)

        // Главный дефект UX-ревью: зелёный заголовок при неподтверждённой защите.
        compose.onNodeWithTag(HOME_TITLE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Туннель поднят").assertIsDisplayed()
        compose.onNodeWithText("Защита не проверена").assertIsDisplayed()
    }

    @Test
    fun confirmedProtectionShowsProtected() {
        setScreen(protected())

        compose.onNodeWithText("Защищено").assertIsDisplayed()
        compose.onNodeWithText("Трафик идёт через туннель").assertIsDisplayed()
    }

    @Test
    fun probeFailureExplainsThereIsNoProtection() {
        setScreen(
            ConnectionStatus.ProtectionFailed(
                ProtectionVerdict.evaluate(false, true, true) as ProtectionVerdict.Failed,
            ),
        )

        compose.onNodeWithText("Защита не подтверждена").assertIsDisplayed()
    }

    @Test
    fun actionButtonCallsConnectWhenNotProtected() {
        var connected = 0
        var disconnected = 0
        setScreen(ConnectionStatus.Disconnected, onConnect = { connected++ }, onDisconnect = { disconnected++ })

        compose.onNodeWithTag(HOME_ACTION_TAG).performClick()

        assertEquals(1, connected)
        assertEquals(0, disconnected)
    }

    @Test
    fun actionButtonCallsDisconnectOnlyWhenProtected() {
        var connected = 0
        var disconnected = 0
        setScreen(protected(), onConnect = { connected++ }, onDisconnect = { disconnected++ })

        compose.onNodeWithTag(HOME_ACTION_TAG).performClick()

        assertEquals(0, connected)
        assertEquals(1, disconnected)
    }

    @Test
    fun disconnectedStateIsNeutral() {
        setScreen(ConnectionStatus.Disconnected)

        compose.onNodeWithText("Отключено").assertIsDisplayed()
        compose.onNodeWithTag(HOME_ACTION_TAG).assertIsDisplayed()
    }
}
