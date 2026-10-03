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
 * На JVM уже проверено, что `presentation()` выбирает правильный текст и цвет;
 * здесь проверяется то, чего на JVM не видно: что это решение действительно
 * доходит до экрана. Тест падает, если кто-то однажды захардкодит зелёный
 * заголовок в разметке, минуя `presentation()`.
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
    fun `поднятый туннель показывает «не проверено», а не «защищено»`() {
        setScreen(ConnectionStatus.VerifyingProtection)

        // Главный дефект UX-ревью: зелёный заголовок при неподтверждённой защите.
        compose.onNodeWithTag(HOME_TITLE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Туннель поднят").assertIsDisplayed()
        compose.onNodeWithText("Защита не проверена").assertIsDisplayed()
    }

    @Test
    fun `подтверждённая защита показывает «защищено»`() {
        setScreen(protected())

        compose.onNodeWithText("Защищено").assertIsDisplayed()
        compose.onNodeWithText("Трафик идёт через туннель").assertIsDisplayed()
    }

    @Test
    fun `сбой пробы объясняет, что защиты нет`() {
        setScreen(
            ConnectionStatus.ProtectionFailed(
                ProtectionVerdict.evaluate(false, true, true) as ProtectionVerdict.Failed,
            ),
        )

        compose.onNodeWithText("Защита не подтверждена").assertIsDisplayed()
    }

    @Test
    fun `кнопка зовёт подключение, когда защиты нет`() {
        var connected = 0
        var disconnected = 0
        setScreen(ConnectionStatus.Disconnected, onConnect = { connected++ }, onDisconnect = { disconnected++ })

        compose.onNodeWithTag(HOME_ACTION_TAG).performClick()

        assertEquals(1, connected)
        assertEquals(0, disconnected)
    }

    @Test
    fun `кнопка зовёт отключение только на подтверждённой защите`() {
        var connected = 0
        var disconnected = 0
        setScreen(protected(), onConnect = { connected++ }, onDisconnect = { disconnected++ })

        compose.onNodeWithTag(HOME_ACTION_TAG).performClick()

        assertEquals(0, connected)
        assertEquals(1, disconnected)
    }

    @Test
    fun `экран отключённого состояния нейтрален`() {
        setScreen(ConnectionStatus.Disconnected)

        compose.onNodeWithText("Отключено").assertIsDisplayed()
        compose.onNodeWithTag(HOME_ACTION_TAG).assertIsDisplayed()
    }
}
