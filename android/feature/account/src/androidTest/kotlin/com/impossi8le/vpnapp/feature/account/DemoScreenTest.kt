package com.impossi8le.vpnapp.feature.account

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Демо-режим как его видит пользователь.
 *
 * Имена методов БЕЗ пробелов и обратных кавычек: эти тесты компилируются в DEX,
 * а D8 до версии 040 запрещает пробелы в именах методов.
 *
 * Жёсткое правило §18: демо НИКОГДА не выглядит как поднятый туннель. Тест
 * падает, если кто-то сделает кнопку активной или заменит нейтральный статус на
 * «защищено» — это ровно тот ложный зелёный, которого демо не должно допускать.
 */
class DemoScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun demoConfig(code: String, name: String = "Страна $code", until: String = "01.01.2027") =
        DemoConfig(code = code, name = name, until = until)

    private fun setScreen(
        configs: List<DemoConfig> = listOf(demoConfig("NL"), demoConfig("DE")),
        selectedCode: String? = null,
        onSelect: (DemoConfig) -> Unit = {},
    ) {
        compose.setContent {
            DemoScreen(configs = configs, selectedCode = selectedCode, onSelect = onSelect)
        }
    }

    @Test
    fun bannerStatesThatDataIsFictionalAndNoTunnelIsRaised() {
        // Ревьюер без подписки должен понимать, что видит макет интерфейса, а не
        // настоящую работу приложения.
        setScreen()

        compose.onNodeWithTag(DEMO_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText("Данные вымышленные", substring = true).assertIsDisplayed()
    }

    @Test
    fun statusReadsAsNotConnectedRatherThanProtected() {
        // Ни в каком состоянии демо не показывает «защищено»: там нечего
        // защищать, туннель не поднимается.
        setScreen()

        compose.onNodeWithTag(DEMO_STATUS_TAG).assertIsDisplayed()
        compose.onNodeWithText("Не подключено").assertIsDisplayed()
    }

    @Test
    fun connectButtonIsDisabledSoItPromisesNothing() {
        // Активная кнопка обещала бы действие, которого в демо не будет.
        setScreen()

        compose.onNodeWithTag(DEMO_ACTION_TAG).assertIsNotEnabled()
    }

    @Test
    fun disabledButtonIsExplainedInPlainWords() {
        setScreen()

        compose.onNodeWithText("В демо-режиме туннель не поднимается").assertIsDisplayed()
    }

    @Test
    fun eachConfigRowCarriesItsOwnDemoDataMark() {
        // «демо-данные» стоит в каждой строке, а не одним баннером сверху: строка
        // может попасть на скриншот отдельно от него.
        setScreen(configs = listOf(demoConfig("NL", name = "Нидерланды")))

        compose.onNodeWithText("демо-данные", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Нидерланды").assertIsDisplayed()
    }

    @Test
    fun tappingConfigReportsIt() {
        val nl = demoConfig("NL")
        var selected: DemoConfig? = null
        setScreen(configs = listOf(nl), onSelect = { selected = it })

        compose.onNodeWithTag(demoConfigTag("NL")).performClick()

        assertEquals(nl, selected)
    }

    @Test
    fun selectedConfigIsMarkedWithABadge() {
        // Выбор видно и цветом фона, и словом: только цветом — это дефект
        // доступности.
        setScreen(configs = listOf(demoConfig("NL")), selectedCode = "NL")

        compose.onNodeWithText("Выбрано").assertIsDisplayed()
    }

    @Test
    fun unselectedConfigCarriesNoBadge() {
        setScreen(configs = listOf(demoConfig("NL"), demoConfig("DE")), selectedCode = "NL")

        // Ровно один бейдж: «Выбрано» не расходится по всем строкам.
        compose.onNodeWithTag(demoConfigTag("DE")).assertIsDisplayed()
        compose.onAllNodesWithText("Выбрано").assertCountEquals(1)
    }

    @Test
    fun tappingAnotherConfigSelectsItNotTheMarkedOne() {
        val de = demoConfig("DE")
        var selected: DemoConfig? = null
        setScreen(configs = listOf(demoConfig("NL"), de), selectedCode = "NL", onSelect = { selected = it })

        compose.onNodeWithTag(demoConfigTag("DE")).performClick()

        assertEquals(de, selected)
    }
}
