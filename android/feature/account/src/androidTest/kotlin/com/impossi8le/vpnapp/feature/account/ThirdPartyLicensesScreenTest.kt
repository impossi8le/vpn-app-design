package com.impossi8le.vpnapp.feature.account

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Экран лицензий сторонних компонентов как его видит пользователь.
 *
 * Имена методов БЕЗ пробелов и обратных кавычек: эти тесты компилируются в DEX,
 * а D8 до версии 040 запрещает пробелы в именах методов.
 *
 * Уведомление о стороннем коде обязательно (issue #24), и оно делится на две
 * части: имя компонента и его лицензия известны из наших данных и показываются
 * всегда, а текст ядра приходит от самой библиотеки. Тест держит их раздельно —
 * иначе недоступное ядро стирало бы уведомление целиком.
 */
class ThirdPartyLicensesScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setScreen(
        engine: EngineLicenseNotice = EngineLicenseNotice.Text("OpenVPN 3 - MPL 2.0"),
        onBack: () -> Unit = {},
    ) {
        compose.setContent { ThirdPartyLicensesScreen(engine = engine, onBack = onBack) }
    }

    @Test
    fun engineIsNamedAndLicensedEvenBeforeItsTextIsShown() {
        setScreen()

        compose.onNodeWithText(ENGINE_COMPONENT_NAME).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(ENGINE_COMPONENT_LICENSE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun engineTextFromTheLibraryIsShownAsIs() {
        // Текст ядра показывается БЕЗ правок: переписанное руками уведомление
        // разъехалось бы с ядром при первом же его обновлении.
        setScreen(engine = EngineLicenseNotice.Text("OpenVPN 3 - MPL 2.0"))

        compose.onNodeWithTag(LICENSES_ENGINE_TEXT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("OpenVPN 3 - MPL 2.0").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun unavailableEngineShowsTheReasonNotAFabricatedLicense() {
        // На JVM и в сборке без libovpn3.so текста нет. Подставить «правильное»
        // уведомление значило бы выдать за факт то, чего библиотека не сообщала.
        setScreen(engine = EngineLicenseNotice.Unavailable("Ядро недоступно"))

        compose.onNodeWithText("Ядро недоступно").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(LICENSES_ENGINE_TEXT_TAG).assertDoesNotExist()
    }

    @Test
    fun unavailableEngineStillNamesTheLicenseFromOurOwnData() {
        setScreen(engine = EngineLicenseNotice.Unavailable("Ядро недоступно"))

        compose.onNodeWithText(ENGINE_COMPONENT_NAME).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(ENGINE_COMPONENT_LICENSE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun everyDependencyIsListedWithItsLicense() {
        // Список — данные, и он должен дойти до экрана целиком: пропущенная
        // зависимость это недостающее уведомление.
        //
        // Имена уникальны, поэтому проверяются точным совпадением. Лицензии —
        // нет: «Apache License 2.0» стоит у двух компонентов (mbedTLS и OpenSSL).
        // Ожидаемое число берётся из самих данных, а не вписано числом: иначе
        // добавление зависимости ломало бы верный экран.
        setScreen()

        THIRD_PARTY_COMPONENTS.forEach { component ->
            compose.onNodeWithText(component.name).performScrollTo().assertIsDisplayed()
        }

        THIRD_PARTY_COMPONENTS
            .groupingBy { it.license }
            .eachCount()
            .forEach { (license, expected) ->
                compose.onAllNodesWithText(license).assertCountEquals(expected)
            }
    }

    @Test
    fun eachDependencyShowsWhyItsLicenseMatters() {
        // Имени и лицензии мало: без одной фразы о том, что лицензия обязывает,
        // уведомление не объясняет, что из него следует.
        setScreen()

        THIRD_PARTY_COMPONENTS.forEach { component ->
            compose.onNodeWithText(component.summary).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    fun lzoIsNotClaimedAsABundledDependency() {
        // GPL-библиотека Oberhumer в сборку не попала: в ядре используется только
        // встроенная распаковка `lzoasym.hpp`. Строка про неё была бы ложью.
        setScreen()

        compose.onNodeWithText("lzo", substring = false).assertDoesNotExist()
    }

    @Test
    fun footnoteSaysFullTextsLiveInTheLibraries() {
        setScreen()

        compose
            .onNodeWithText("Полные тексты лицензий не перепечатаны", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun backReportsIntent() {
        var back = false
        setScreen(onBack = { back = true })

        compose.onNodeWithTag(LICENSES_BACK_TAG).performClick()

        assertEquals(true, back)
    }

    @Test
    fun screenIsAboutLicencesNotAboutProtection() {
        // Экран называет сторонний код и его лицензии. Обещаний безопасности и
        // анонимности тут быть не должно.
        setScreen()

        listOf("защищ", "анонимн", "безопасн").forEach { word ->
            compose
                .onNodeWithText(word, substring = true, ignoreCase = true)
                .assertDoesNotExist()
        }
    }
}
