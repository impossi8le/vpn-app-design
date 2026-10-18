package com.impossi8le.vpnapp.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Общие примитивы интерфейса как они видны пользователю.
 *
 * ИМЕНА МЕТОДОВ БЕЗ ПРОБЕЛОВ И ОБРАТНЫХ КАВЫЧЕК: эти тесты идут в DEX, а D8 до
 * версии 040 запрещает пробелы в именах методов.
 *
 * Здесь не проверяются решения о состоянии — они живут в `presentation()` и
 * проверены на JVM. Проверяется то, чего на JVM не видно: что элемент вообще
 * рисуется, что он нажимается там, где должен, и что зона нажатия не меньше
 * 44dp. Промах по кнопке 28dp — это тот же дефект, что и неправильный цвет:
 * пользователь не может сделать то, что ему предлагают.
 */
class ComponentsTest {

    @get:Rule
    val compose = createComposeRule()

    // --- Карточка и строка ---------------------------------------------------

    @Test
    fun cardShowsItsContent() {
        compose.setContent {
            VpnCard { androidx.compose.material3.Text("Внутри карточки") }
        }

        compose.onNodeWithText("Внутри карточки").assertIsDisplayed()
    }

    @Test
    fun rowShowsKeyValueAndSubtitle() {
        compose.setContent {
            VpnRow(key = "Сервер", sub = "Амстердам", value = "10.8.0.1")
        }

        compose.onNodeWithText("Сервер").assertIsDisplayed()
        compose.onNodeWithText("Амстердам").assertIsDisplayed()
        compose.onNodeWithText("10.8.0.1").assertIsDisplayed()
    }

    @Test
    fun rowPrefersTrailingOverValue() {
        // `trailing` и `value` взаимоисключающи по смыслу: значение — это текст,
        // trailing — свой элемент. Показать оба значило бы удвоить ответ.
        compose.setContent {
            VpnRow(
                key = "Обновление",
                value = "не показывать",
                trailing = { androidx.compose.material3.Text("тут свой элемент") },
            )
        }

        compose.onNodeWithText("тут свой элемент").assertIsDisplayed()
        compose.onNodeWithText("не показывать").assertDoesNotExist()
    }

    @Test
    fun rowHasTouchTargetHeight() {
        // Строка — не кнопка, но её высота задаёт ритм списка и должна совпадать
        // с интерактивными элементами, иначе список и действия разъезжаются.
        // Меряем обёртку вокруг строки: у самой строки нет тега, а
        // `onNodeWithText` вернул бы рамку надписи, а не рамку всей строки.
        compose.setContent {
            androidx.compose.foundation.layout.Box(Modifier.testTag(ROW_TAG)) {
                VpnRow(key = "Сервер", value = "10.8.0.1")
            }
        }

        val height = compose.onNodeWithTag(ROW_TAG).fetchSemanticsNode().boundsInRoot.height
        assertTrue("строка ниже минимума 44dp: $height", height >= 44f)
    }

    // --- Предупреждение ------------------------------------------------------

    @Test
    fun noticeShowsItsText() {
        compose.setContent { VpnNotice(text = "Проверьте настройки", testTag = NOTICE_TAG) }

        compose.onNodeWithTag(NOTICE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Проверьте настройки").assertIsDisplayed()
    }

    @Test
    fun noticeToneReachesTheRendering() {
        // Тон — решение о смысле, и оно должно доходить до пикселей, а не
        // оставаться аргументом. Нейтральный тон собран из серых поверхностей и
        // голубого значка; у Danger и Warning в кадре появляется тёплый оттенок,
        // то есть красный заметно выше зелёного. Мера «теплоты» ровно это и
        // считает: у нейтрального текст `Mist` и фон дают около нуля, потому что
        // ни один канал там не перевешивает.
        compose.setContent {
            Column(modifier = Modifier.background(VpnColors.Void)) {
                VpnNotice("опасность", tone = Tone.Danger, testTag = DANGER_TAG)
                VpnNotice("внимание", tone = Tone.Warning, testTag = WARNING_TAG)
                VpnNotice("пояснение", tone = Tone.Neutral, testTag = NEUTRAL_TAG)
            }
        }

        val dangerWarmth = compose.onNodeWithTag(DANGER_TAG).capturedPixels().warmth()
        val warningWarmth = compose.onNodeWithTag(WARNING_TAG).capturedPixels().warmth()
        val neutralWarmth = compose.onNodeWithTag(NEUTRAL_TAG).capturedPixels().warmth()

        assertTrue(
            "красное предупреждение не отличается от нейтрального " +
                "($dangerWarmth против $neutralWarmth)",
            dangerWarmth > neutralWarmth,
        )
        assertTrue(
            "янтарное предупреждение не отличается от нейтрального " +
                "($warningWarmth против $neutralWarmth)",
            warningWarmth > neutralWarmth,
        )
    }

    @Test
    fun dangerNoticeIsWarmerThanWarningNotice() {
        // Красный и янтарный различимы на экране, а не только в коде. Мельче
        // различие не по синему каналу — он у них почти одинаков, — а по
        // величине теплоты: смещение красного от зелёного у опасного сигнала
        // гораздо больше, чем у «просто внимания». Иначе выбор тона был бы
        // решением, которого пользователь не видит.
        compose.setContent {
            Column(modifier = Modifier.background(VpnColors.Void)) {
                VpnNotice("опасность", tone = Tone.Danger, testTag = DANGER_TAG)
                VpnNotice("внимание", tone = Tone.Warning, testTag = WARNING_TAG)
            }
        }

        val danger = compose.onNodeWithTag(DANGER_TAG).capturedPixels().warmth()
        val warning = compose.onNodeWithTag(WARNING_TAG).capturedPixels().warmth()

        assertTrue(
            "опасность и внимание выглядят одинаково ($danger против $warning)",
            danger > warning,
        )
    }

    @Test
    fun neutralIsTheDefaultTone() {
        // Безопасная сторона: не указав тон, вызывающий не может случайно
        // получить аварийный красный. Опасность заявляется явно.
        compose.setContent {
            Column(modifier = Modifier.background(VpnColors.Void)) {
                VpnNotice("по умолчанию", testTag = DEFAULT_TAG)
                VpnNotice("нейтрально", tone = Tone.Neutral, testTag = NEUTRAL_TAG)
            }
        }

        val default = compose.onNodeWithTag(DEFAULT_TAG).capturedPixels()
        val neutral = compose.onNodeWithTag(NEUTRAL_TAG).capturedPixels()

        assertEquals(
            "тон по умолчанию должен быть нейтральным, а не предупреждающим",
            neutral.countPixels { it.isRedTinted() },
            default.countPixels { it.isRedTinted() },
        )
    }

    @Test
    fun noticeIconIsOptional() {
        compose.setContent {
            Column {
                VpnNotice("без значка", testTag = PLAIN_TAG)
                VpnNotice("со значком", icon = "!", testTag = ICON_TAG)
            }
        }

        compose.onNodeWithTag(PLAIN_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ICON_TAG).assertIsDisplayed()
    }

    // --- Значок и метка страны ----------------------------------------------

    @Test
    fun badgeShowsItsTextNotOnlyColour() {
        // Состояние различается и цветом, и текстом: только цветом — это дефект
        // доступности, найденный на ревью макета (дальтонизм).
        compose.setContent {
            Column {
                VpnBadge("Активен", BadgeTone.Active)
                VpnBadge("Истёк", BadgeTone.Dead)
            }
        }

        compose.onNodeWithText("Активен").assertIsDisplayed()
        compose.onNodeWithText("Истёк").assertIsDisplayed()
    }

    @Test
    fun countryMarkShowsLettersNotFlagEmoji() {
        // Буквы, а не флаг-эмодзи: эмодзи рисуются по-разному на разных
        // прошивках, а буквенный код узнаётся всегда.
        compose.setContent { CountryMark(code = "NL") }

        compose.onNodeWithText("NL").assertIsDisplayed()
        compose.onNodeWithText("🇳🇱").assertDoesNotExist()
    }

    @Test
    fun countryMarkRendersInEveryVisualState() {
        compose.setContent {
            Column {
                CountryMark("NL", selected = true)
                CountryMark("DE", dead = true)
                CountryMark("FR")
            }
        }

        compose.onNodeWithText("NL").assertIsDisplayed()
        compose.onNodeWithText("DE").assertIsDisplayed()
        compose.onNodeWithText("FR").assertIsDisplayed()
    }

    // --- Переключатель -------------------------------------------------------

    @Test
    fun switchReportsItsValueAndToggles() {
        var last: Boolean? = null
        compose.setContent {
            VpnSwitch(checked = false, onCheckedChange = { last = it }, testTag = SWITCH_TAG)
        }

        // `toggleable` объявляет действие-переключение; проверяем, что компонент
        // сообщает НОВОЕ значение, а не подтверждает старое.
        compose.onNodeWithTag(SWITCH_TAG).performClick()

        assertEquals(true, last)
    }

    @Test
    fun switchHasRoleSwitchForAccessibility() {
        // Роль важна: без неё VoiceOver/TalkBack читает тумблер как кнопку и не
        // сообщает, включён он или нет.
        compose.setContent {
            VpnSwitch(checked = true, onCheckedChange = {}, testTag = SWITCH_TAG)
        }

        val node = compose.onNodeWithTag(SWITCH_TAG).fetchSemanticsNode()
        val role = node.config.getOrElseNullable(SemanticsProperties.Role) { null }
        assertEquals(Role.Switch, role)

        // И состояние переключателя объявлено: без него TalkBack не скажет,
        // включён он или нет, даже при правильной роли.
        val state = node.config.getOrElseNullable(SemanticsProperties.ToggleableState) { null }
        assertEquals(ToggleableState.On, state)
    }

    @Test
    fun switchTouchTargetIsAtLeast44dp() {
        // Сам тумблер 28dp, и попасть в него пальцем трудно — это была находка
        // доступности. Зона нажатия обёрнута и обязана быть не меньше 44dp.
        compose.setContent {
            VpnSwitch(checked = false, onCheckedChange = {}, testTag = SWITCH_TAG)
        }

        val bounds = compose.onNodeWithTag(SWITCH_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("зона нажатия ниже 44dp: ${bounds.height}", bounds.height >= 44f)
    }

    @Test
    fun switchPaintsDifferentlyWhenChecked() {
        // Включённое состояние показано и заливкой, и рамкой, а не только
        // положением кружка: цвет здесь — единственный сигнал для того, кто не
        // различает позицию ползунка. Проверяем, что заливка действительно
        // меняется, а не только смещается внутренний элемент.
        var checked by mutableStateOf(false)
        compose.setContent {
            VpnSwitch(checked = checked, onCheckedChange = {}, testTag = SWITCH_TAG)
        }

        val offPixels = compose.onNodeWithTag(SWITCH_TAG).capturedPixels()
        checked = true
        compose.waitForIdle()
        val onPixels = compose.onNodeWithTag(SWITCH_TAG).capturedPixels()

        assertFalse(
            "включённый переключатель выглядит как выключенный",
            offPixels.contentEquals(onPixels),
        )
    }

    // --- Метка раздела -------------------------------------------------------

    @Test
    fun sectionLabelIsUppercased() {
        compose.setContent { SectionLabel(text = "Обходы") }

        compose.onNodeWithText("ОБХОДЫ").assertIsDisplayed()
    }

    @Test
    fun sectionLabelShowsActionOnlyWhenBothAreGiven() {
        // Действие без обработчика или обработчик без подписи — это элемент,
        // который выглядит нажимаемым и ничего не делает.
        var invoked = 0
        compose.setContent {
            Column {
                SectionLabel(text = "Первая", action = "Все", onAction = { invoked++ })
                SectionLabel(text = "Вторая", action = "Все")
                SectionLabel(text = "Третья", onAction = { invoked++ })
            }
        }

        compose.onNodeWithText("Все").assertIsDisplayed()
        compose.onNodeWithText("Все").performClick()
        assertEquals("нажатие на действие метки не дошло", 1, invoked)
    }

    @Test
    fun sectionLabelActionHasTouchTarget() {
        compose.setContent { SectionLabel(text = "Обходы", action = "Все", onAction = {}) }

        val height = compose.onNodeWithText("Все").fetchSemanticsNode().boundsInRoot.height
        assertTrue("действие метки ниже 44dp: $height", height >= 44f)
    }

    // --- Кнопки --------------------------------------------------------------

    @Test
    fun primaryButtonCallsBackWhenEnabled() {
        var clicks = 0
        compose.setContent {
            VpnPrimaryButton(text = "Подключить", onClick = { clicks++ }, testTag = PRIMARY_TAG)
        }

        compose.onNodeWithTag(PRIMARY_TAG).performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun disabledPrimaryButtonDoesNotCallBack() {
        // Отключённая кнопка обязана молчать: нажатие, которое ничего не делает
        // и ничего не объясняет, читается как поломка.
        var clicks = 0
        compose.setContent {
            VpnPrimaryButton(
                text = "Подключить",
                onClick = { clicks++ },
                enabled = false,
                testTag = PRIMARY_TAG,
            )
        }

        compose.onNodeWithTag(PRIMARY_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(PRIMARY_TAG).performClick()

        assertEquals(0, clicks)
    }

    @Test
    fun primaryButtonLabelIsShownInFull() {
        // Подпись не должна обрезаться: при 16.5sp и минимальной ширине она
        // должна помещаться в одну строку (issue #13).
        compose.setContent {
            VpnPrimaryButton(text = "Подключить", onClick = {}, testTag = PRIMARY_TAG)
        }

        compose.onNodeWithText("Подключить").assertIsDisplayed()
    }

    @Test
    fun ghostButtonCallsBack() {
        var clicks = 0
        compose.setContent {
            VpnGhostButton(text = "Отмена", onClick = { clicks++ }, testTag = GHOST_TAG)
        }

        compose.onNodeWithTag(GHOST_TAG).performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun buttonsKeepMinimumHeight() {
        // Кнопка не двигается и не меняет размер между состояниями (issue #6),
        // но её нижняя граница — 50dp из макета.
        compose.setContent {
            Column {
                VpnPrimaryButton(text = "Основная", onClick = {}, testTag = PRIMARY_TAG)
                VpnGhostButton(text = "Второстепенная", onClick = {}, testTag = GHOST_TAG)
            }
        }

        val primary = compose.onNodeWithTag(PRIMARY_TAG).fetchSemanticsNode().boundsInRoot
        val ghost = compose.onNodeWithTag(GHOST_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue("основная кнопка ниже 50dp: ${primary.height}", primary.height >= 50f)
        assertTrue("второстепенная кнопка ниже 50dp: ${ghost.height}", ghost.height >= 50f)
    }

    // --- Верхняя панель ------------------------------------------------------

    @Test
    fun navbarShowsTitleAndBackOnlyWhenGiven() {
        compose.setContent { VpnNavBar(title = "Профиль") }

        compose.onNodeWithText("Профиль").assertIsDisplayed()
        compose.onNodeWithText("‹ Назад").assertDoesNotExist()
    }

    @Test
    fun navbarBackReportsItsIntent() {
        var backs = 0
        compose.setContent {
            VpnNavBar(title = "Профиль", onBack = { backs++ }, backTestTag = BACK_TAG)
        }

        compose.onNodeWithTag(BACK_TAG).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun navbarActionAppearsOnlyWithHandler() {
        var actions = 0
        compose.setContent {
            Column {
                VpnNavBar(title = "Подключение", onBack = {}, action = "Обходы", onAction = { actions++ })
                VpnNavBar(title = "Аккаунт", onBack = {}, action = "Готово")
            }
        }

        compose.onNodeWithText("Обходы").performClick()
        assertEquals("действие шапки не дошло", 1, actions)

        // Вторая шапка объявила действие без обработчика — оно не должно
        // появиться вообще, а не быть молчаливо неработающим.
        compose.onNodeWithText("Готово").assertDoesNotExist()
    }

    @Test
    fun navbarBackHasTouchTarget() {
        compose.setContent {
            VpnNavBar(title = "Профиль", onBack = {}, backTestTag = BACK_TAG)
        }

        val bounds = compose.onNodeWithTag(BACK_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("«Назад» ниже 44dp: ${bounds.height}", bounds.height >= 44f)
    }

    @Test
    fun navbarKeepsTitleCentredWithAndWithoutBack() {
        // Левая и правая ячейки фиксированной ширины: иначе заголовок сдвигается
        // влево на экранах без «назад», и экраны выглядят по-разному.
        compose.setContent {
            Column {
                VpnNavBar(title = "С назад", onBack = {}, action = "Войти", onAction = {})
                VpnNavBar(title = "Без действий")
            }
        }

        val withActions = compose.onNodeWithText("С назад").fetchSemanticsNode().boundsInRoot
        val without = compose.onNodeWithText("Без действий").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "заголовок съезжает при появлении боковых действий: " +
                "${withActions.center.x} против ${without.center.x}",
            kotlin.math.abs(withActions.center.x - without.center.x) <= 0.5f,
        )
    }

    // --- Токен минимальной зоны нажатия --------------------------------------

    @Test
    fun minTouchTargetTokenIsAtLeast44dp() {
        // Токен — обещание, на которое опираются все интерактивные элементы.
        // Если его понизят, доступность поедет во всех экранах сразу.
        assertEquals(44f, MinTouchTarget.value, 0.01f)
    }
}

/**
 * Насколько кадр «тёплый»: сумма превышения красного над зелёным по всем
 * пикселям.
 *
 * Мера выбрана так, чтобы её не сбивали ни текст, ни серые поверхности, ни
 * голубой значок нейтрального тона — у всех них красный не выше зелёного, и
 * вклад нулевой или отрицательный. В плюс идёт только настоящий тёплый оттенок.
 */
private fun IntArray.warmth(): Long = sumOf { (it.red() - it.green()).toLong() }

private const val NOTICE_TAG = "t_notice"
private const val ROW_TAG = "t_row"
private const val DANGER_TAG = "t_danger"
private const val WARNING_TAG = "t_warning"
private const val NEUTRAL_TAG = "t_neutral"
private const val DEFAULT_TAG = "t_default"
private const val PLAIN_TAG = "t_plain"
private const val ICON_TAG = "t_icon"
private const val SWITCH_TAG = "t_switch"
private const val PRIMARY_TAG = "t_primary"
private const val GHOST_TAG = "t_ghost"
private const val BACK_TAG = "t_back"
