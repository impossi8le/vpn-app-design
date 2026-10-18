package com.impossi8le.vpnapp.feature.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Шиты и баннеры подключения как их видит пользователь.
 *
 * ИМЕНА МЕТОДОВ БЕЗ ПРОБЕЛОВ И ОБРАТНЫХ КАВЫЧЕК: инструментальные тесты идут в
 * DEX, а D8 до версии 040 запрещает пробелы в именах методов.
 *
 * Главная проверка здесь — не «диалог показан», а что он ведёт себя как
 * МОДАЛЬНОЕ окно: взаимодействовать с тем, что лежит под ним, невозможно. Это
 * живой дефект (issue #30): затемнение в `SheetScaffold` только рисовалось, тап
 * проходил сквозь него в список, и подключение переключалось раньше, чем
 * пользователь ответил. Тест «элемент виден» такой возврат НЕ ловит — на
 * сломанной версии он тоже зелёный.
 *
 * Поэтому под каждым шитом лежит кликабельный «фон», и проверяются ДВА факта:
 * что с шитом тап до фона не доходит и что без шита тот же тап доходит. Второй
 * не украшение: без него первый проходил бы вхолостую, потому что не отличал
 * «касание перехватили» от «элемент фона и так не нажимается».
 *
 * Отдельно проверяется, что шит НЕ закрывается тапом по затемнению. Закрытие
 * здесь необратимо «не тем» решением: за кадром рвётся соединение, и молчаливая
 * отмена по случайному тапу — та же ошибка, что и молчаливое переключение.
 */
class VpnSheetsTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Касания, дошедшие до фона под шитом.
     *
     * Поле теста, а не локальная переменная в `setContent`: обработчик нажатия
     * выполняется на UI-потоке, а читаем его здесь после `waitForIdle`, так что
     * список — общая точка этих двух потоков.
     */
    private val behindTaps = mutableListOf<String>()

    /**
     * Стенд: кликабельный фон и, если передан, шит поверх него.
     *
     * Порядок важен и повторяет `MainActivity`: шит лежит отдельным элементом
     * ПОСЛЕ содержимого, а не внутри него. Иначе `fillMaxSize` шита раздувал бы
     * фон, и перехват касаний проверялся бы не на той раскладке.
     */
    private fun setStand(sheet: (@Composable () -> Unit)? = null) {
        compose.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(BEHIND_TAG)
                        .clickable { behindTaps.add(BEHIND_TAG) },
                )
                if (sheet != null) sheet()
            }
        }
    }

    /** Тап по центру фона — самая перекрытая затемнением точка экрана. */
    private fun tapBehind() {
        compose.onNodeWithTag(BEHIND_TAG).performClick()
        compose.waitForIdle()
    }

    // --- Прайминг-шит -----------------------------------------------------

    @Test
    fun primingSheetExplainsTheSystemPromptBeforeItAppears() {
        setStand { PrimingSheet(onContinue = {}, onCancel = {}) }

        compose.onNodeWithTag(PRIMING_SHEET_TAG).assertIsDisplayed()
        // Текст обязан снять страх перед системным диалогом: без объяснения
        // пользователь жмёт «Не разрешать», а диалог показывается один раз.
        compose.onNodeWithText("Система спросит разрешение", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Понятно, продолжить").assertIsDisplayed()
    }

    @Test
    fun primingContinueReportsContinueIntent() {
        var continued = false
        var cancelled = false
        setStand { PrimingSheet(onContinue = { continued = true }, onCancel = { cancelled = true }) }

        compose.onNodeWithTag(PRIMING_CONFIRM_TAG).performClick()

        assertTrue(continued)
        assertEquals("продолжение не отмена", false, cancelled)
    }

    @Test
    fun primingCancelReportsCancelIntent() {
        var continued = false
        var cancelled = false
        setStand { PrimingSheet(onContinue = { continued = true }, onCancel = { cancelled = true }) }

        compose.onNodeWithTag(PRIMING_CANCEL_TAG).performClick()

        assertTrue(cancelled)
        assertEquals("отмена не продолжение", false, continued)
    }

    @Test
    fun primingSheetBlocksTheScreenUnderIt() {
        setStand { PrimingSheet(onContinue = {}, onCancel = {}) }

        tapBehind()

        assertTrue("тап прошёл сквозь шит на экран под ним", behindTaps.isEmpty())
    }

    @Test
    fun primingSheetDoesNotCloseOnAScrimTap() {
        // Затемнение действий не имеет намеренно: закрыть шит можно только
        // кнопкой, иначе ответ «за пользователя» даёт случайный тап.
        var continued = false
        var cancelled = false
        setStand { PrimingSheet(onContinue = { continued = true }, onCancel = { cancelled = true }) }

        tapBehind()

        assertEquals("тап по затемнению не должен подтверждать", false, continued)
        assertEquals("тап по затемнению не должен отменять", false, cancelled)
        compose.onNodeWithTag(PRIMING_SHEET_TAG).assertIsDisplayed()
    }

    @Test
    fun withoutAnySheetTheTapReachesTheScreen() {
        // Контрольный тест стенда. Без него предыдущие проверки блокировки были
        // бы зелёными и в том случае, если бы фон вообще не принимал нажатия.
        setStand(sheet = null)

        tapBehind()

        assertEquals("фон обязан нажиматься, когда шита нет", 1, behindTaps.size)
    }

    // --- Подтверждение смены страны ---------------------------------------

    @Test
    fun switchSheetNamesTheCountryItIsAboutToSwitchTo() {
        setStand {
            SwitchCountrySheet(
                countryName = "Турция",
                dontAskAgain = false,
                onDontAskAgainChange = {},
                onConfirm = {},
                onCancel = {},
            )
        }

        // Заголовок собран из имени страны: «Переключиться на Турцию?» понятнее
        // безличного «Подтвердите действие». Строка проверяется подстрокой, а не
        // целиком: имя страны в заголовке встаёт в винительный падеж, и подгонять
        // падеж под литерал теста значило бы закреплять неверную форму в тесте.
        compose.onNodeWithText("Переключиться на", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Турция", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Текущее подключение прервётся", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun switchConfirmReportsConfirmIntent() {
        var confirmed = false
        setStand {
            SwitchCountrySheet(
                countryName = "Турция",
                dontAskAgain = false,
                onDontAskAgainChange = {},
                onConfirm = { confirmed = true },
                onCancel = {},
            )
        }

        compose.onNodeWithTag(SWITCH_CONFIRM_TAG).performClick()

        assertTrue(confirmed)
    }

    @Test
    fun switchSheetBlocksTheListUnderIt() {
        // Это и был issue #30: затемнение рисовалось, но тап по списку под ним
        // проходил насквозь и переключал подключение до ответа пользователя.
        setStand {
            SwitchCountrySheet(
                countryName = "Турция",
                dontAskAgain = false,
                onDontAskAgainChange = {},
                onConfirm = {},
                onCancel = {},
            )
        }

        tapBehind()

        assertTrue("тап прошёл сквозь шит смены страны", behindTaps.isEmpty())
    }

    @Test
    fun switchSheetDoesNotConfirmOnAScrimTap() {
        var confirmed = false
        setStand {
            SwitchCountrySheet(
                countryName = "Турция",
                dontAskAgain = false,
                onDontAskAgainChange = {},
                onConfirm = { confirmed = true },
                onCancel = {},
            )
        }

        tapBehind()

        assertEquals("тап мимо кнопки не должен переключать страну", false, confirmed)
        compose.onNodeWithTag(SWITCH_SHEET_TAG).assertIsDisplayed()
    }

    @Test
    fun dontAskAgainCheckboxReportsTheNewValue() {
        var received: Boolean? = null
        setStand {
            SwitchCountrySheet(
                countryName = "Турция",
                dontAskAgain = false,
                onDontAskAgainChange = { received = it },
                onConfirm = {},
                onCancel = {},
            )
        }

        compose.onNodeWithTag(SWITCH_DONT_ASK_TAG).performClick()

        // Именно `true`: это переключатель, а не «нажали». Состояние принадлежит
        // вызывающему экрану, поэтому важно, что наружу ушло новое значение.
        assertEquals(true, received)
    }

    @Test
    fun dontAskAgainCheckboxShowsTheStateItWasGiven() {
        // Флаг обратимый: он лишь выключает тумблер в аккаунте, и шит обязан
        // показывать переданное состояние, а не помнить своё.
        setStand {
            SwitchCountrySheet(
                countryName = "Турция",
                dontAskAgain = true,
                onDontAskAgainChange = {},
                onConfirm = {},
                onCancel = {},
            )
        }

        compose.onNodeWithTag(SWITCH_DONT_ASK_TAG).assertIsOn()
    }

    // --- Баннер окна без защиты -------------------------------------------

    @Test
    fun unprotectedBannerIsAVisibleRowNotAModalSheet() {
        setStand { UnprotectedTrafficBanner() }

        compose.onNodeWithTag(UNPROTECTED_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText("Трафик временно не защищён", substring = true).assertIsDisplayed()

        // Баннер НЕ должен быть модальным: переключение идёт секундами, и
        // блокировать экран на это время незачем — это строка, а не диалог.
        tapBehind()
        assertEquals("баннер не вправе перехватывать касания", 1, behindTaps.size)
    }

    // --- Эскалация ожидания -----------------------------------------------

    @Test
    fun escalationAtTenSecondsExplainsTheSlowServerWithoutOfferingExits() {
        var retried = false
        setStand {
            WaitingEscalation(
                seconds = 10,
                countryName = "Финляндия",
                onRetry = { retried = true },
                onChooseOther = {},
            )
        }

        compose.onNodeWithTag(WAITING_ESCALATION_TAG).assertIsDisplayed()
        compose.onNodeWithText("Сервер отвечает медленно", substring = true).assertIsDisplayed()
        // Полутора секунд терпения пользователю ещё хватает: «Выбрать другое»
        // здесь подгоняло бы бросить попытку раньше времени.
        compose.onNodeWithText("Повторить").assertDoesNotExist()
        compose.onNodeWithText("Выбрать другое").assertDoesNotExist()

        tapBehind()
        assertEquals("подсказка о медленном сервере не блокирует экран", 1, behindTaps.size)
        assertEquals(false, retried)
    }

    @Test
    fun escalationAfterThirtySecondsShowsAFailureWithBothExits() {
        var retried = false
        setStand {
            WaitingEscalation(
                seconds = 31,
                countryName = "Финляндия",
                onRetry = { retried = true },
                onChooseOther = {},
            )
        }

        // Дольше 30 секунд — уже не процесс, а состоявшийся отказ: заголовок
        // обязан перестать обещать подключение.
        compose.onNodeWithText("Долго не отвечает").assertIsDisplayed()
        compose.onNodeWithText("Подключение не состоялось", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Повторить").assertIsDisplayed()
        compose.onNodeWithText("Выбрать другое").assertIsDisplayed()

        compose.onNodeWithText("Повторить").performClick()
        assertTrue("«Повторить» обязана сообщать намерение повторить", retried)
    }

    private companion object {
        /** Метка фона под шитом: то, что НЕ должно получать касания. */
        const val BEHIND_TAG = "behind_the_sheet"
    }
}
