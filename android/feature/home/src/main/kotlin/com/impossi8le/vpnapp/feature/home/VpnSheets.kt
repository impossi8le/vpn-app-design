package com.impossi8le.vpnapp.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.MinTouchTarget
import com.impossi8le.vpnapp.core.ui.Tone
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnGhostButton
import com.impossi8le.vpnapp.core.ui.VpnNotice
import com.impossi8le.vpnapp.core.ui.VpnPrimaryButton
import com.impossi8le.vpnapp.core.ui.VpnRadii

/**
 * Модальные окна и баннеры, собранные по `docs/design/mockup.html`.
 *
 * Зачем отдельно от `HomeScreen`: это всплывающие слои поверх экрана, и они не
 * меняются вместе с состоянием подключения. Прайминг-шит и подтверждение смены
 * страны нужны ровно в один момент каждый, а смешивать их с раскладкой главного
 * экрана — значит тащить туда диалоговую логику, которую потом не проверить.
 *
 * Вид собран из общих компонентов `core:ui` намеренно: у макета единые отступы,
 * радиусы и зоны касания, и копия этих значений в каждом шите разошлась бы при
 * первой же правке.
 */

// Теги держим константами: тест не должен искать элемент по видимому тексту —
// текст правят чаще, чем смысл элемента.
const val PRIMING_SHEET_TAG = "priming_sheet"
const val PRIMING_CONFIRM_TAG = "priming_confirm"
const val PRIMING_CANCEL_TAG = "priming_cancel"
const val SWITCH_SHEET_TAG = "switch_sheet"
const val SWITCH_CONFIRM_TAG = "switch_confirm"
const val SWITCH_CANCEL_TAG = "switch_cancel"
const val SWITCH_DONT_ASK_TAG = "switch_dont_ask"
const val UNPROTECTED_BANNER_TAG = "unprotected_banner"
const val WAITING_ESCALATION_TAG = "waiting_escalation"

/**
 * Прайминг-шит перед системным диалогом VPN.
 *
 * Самый частый безвозвратный отказ происходит здесь: пользователь видит
 * незнакомый системный запрос, пугается и жмёт «Не разрешать», а диалог
 * показывается один раз. Обойти системный диалог нельзя, но можно объяснить его
 * заранее — что это стандартный вопрос, и что кнопка «Разрешить» не даёт доступа
 * к чему-то лишнему.
 */
@Composable
fun PrimingSheet(
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetScaffold(modifier = modifier, testTag = PRIMING_SHEET_TAG) {
        Text(
            "Сейчас появится системный запрос",
            color = VpnColors.Bone,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 24.sp,
        )
        Text(
            "Система спросит разрешение добавить VPN-конфигурацию. Это стандартный " +
                "вопрос — так система защищает вас. Нажмите «Разрешить», без этого " +
                "подключение невозможно.",
            color = VpnColors.TextSecondary,
            fontSize = 13.5.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 9.dp, bottom = 18.dp),
        )
        VpnPrimaryButton(
            text = "Понятно, продолжить",
            onClick = onContinue,
            testTag = PRIMING_CONFIRM_TAG,
        )
        VpnGhostButton(
            text = "Отмена",
            onClick = onCancel,
            testTag = PRIMING_CANCEL_TAG,
            modifier = Modifier.padding(top = 9.dp),
        )
    }
}

/**
 * Подтверждение смены страны.
 *
 * Смена страны на секунды рвёт текущее соединение, и это надо сказать до
 * действия: во время загрузки или звонка обрыв замечают уже по факту.
 *
 * Чекбокс «Больше не спрашивать» — НЕ необратимый флаг. Он лишь выключает тумблер
 * «Подтверждать смену страны» в аккаунте, который можно вернуть обратно. В первой
 * версии макета это был безвозвратный отказ от предупреждения, и его убрали: цена
 * ошибки — молчаливый разрыв в момент звонка.
 *
 * @param countryName страна, НА которую переключаются: заголовок собирается из
 *   неё, потому что «Переключиться на Турцию?» понятнее безличного подтверждения.
 * @param dontAskAgain текущее значение чекбокса — состояние принадлежит вызывающему
 *   экрану, здесь только вид.
 */
@Composable
fun SwitchCountrySheet(
    countryName: String,
    dontAskAgain: Boolean,
    onDontAskAgainChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetScaffold(modifier = modifier, testTag = SWITCH_SHEET_TAG) {
        Text(
            "Переключиться на $countryName?",
            color = VpnColors.Bone,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 24.sp,
        )
        Text(
            "Текущее подключение прервётся на несколько секунд. Загрузки, звонки и " +
                "видео могут оборваться.",
            color = VpnColors.TextSecondary,
            fontSize = 13.5.sp,
            lineHeight = 21.sp,
            modifier = Modifier.padding(top = 9.dp, bottom = 18.dp),
        )
        VpnPrimaryButton(
            text = "Переключить",
            onClick = onConfirm,
            testTag = SWITCH_CONFIRM_TAG,
        )
        VpnGhostButton(
            text = "Отмена",
            onClick = onCancel,
            testTag = SWITCH_CANCEL_TAG,
            modifier = Modifier.padding(top = 9.dp),
        )
        VpnCheckbox(
            checked = dontAskAgain,
            onCheckedChange = onDontAskAgainChange,
            label = "Больше не спрашивать",
            testTag = SWITCH_DONT_ASK_TAG,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/**
 * Баннер окна без защиты.
 *
 * Это НЕ диалог, а строка внутри экрана: во время переключения соединения трафик
 * на секунды идёт мимо туннеля, и об этом надо помнить, пока идёт переключение, а
 * не подтверждать это один раз в модалке.
 *
 * Красный здесь — единственное оправданное место: пользователь действительно
 * остаётся без защиты, пусть и ненадолго. Поэтому не «предупреждение», а
 * [Tone.Danger].
 */
@Composable
fun UnprotectedTrafficBanner(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        VpnNotice(
            text = "Трафик временно не защищён. Идёт переключение между подключениями.",
            tone = Tone.Danger,
            icon = "!",
            testTag = UNPROTECTED_BANNER_TAG,
        )
    }
}

/**
 * Общая оболочка шита: затемнение во весь экран и панель, прижатая к низу.
 *
 * Отдельная функция, а не `ModalBottomSheet` из Material: у макета свой цвет и
 * радиус скругления панели, а Material навязывает свою высоту тени и отступы,
 * которые потом приходится переопределять точечно.
 */
@Composable
private fun SheetScaffold(
    modifier: Modifier = Modifier,
    testTag: String,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(testTag),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Затемнение. Перекрывает экран целиком, чтобы фокус остался на шите.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VpnColors.Void.copy(alpha = 0.72f)),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(VpnColors.Obsidian)
                .border(
                    width = 0.5.dp,
                    color = VpnColors.Edge,
                    shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
                )
                // Нижний отступ с запасом под системную зону навигации.
                .padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 26.dp)
                .verticalScroll(rememberScrollState()),
        ) { content() }
    }
}

/**
 * Чекбокс с меткой.
 *
 * Свой, а не `Checkbox` из Material: у макета квадрат 22dp со скруглением 6dp, и
 * вся строка — одна зона касания ≥44pt (у макета было 23pt, это находка
 * доступности). Состояние показано и заливкой, и галочкой: только цветом было бы
 * неразличимо при дальтонизме.
 */
@Composable
private fun VpnCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Checkbox,
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(VpnRadii.Small.dp))
                .background(if (checked) VpnColors.Ice else VpnColors.Void)
                .border(
                    width = 1.5.dp,
                    color = if (checked) VpnColors.Ice else VpnColors.Edge,
                    shape = RoundedCornerShape(VpnRadii.Small.dp),
                )
                .size(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text(
                    "✓",
                    color = VpnColors.Void,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Text(
            label,
            color = VpnColors.TextSecondary,
            fontSize = 13.5.sp,
        )
    }
}

/**
 * Стадия ожидания подключения.
 *
 * Пороги взяты из макета и вынесены в чистую функцию [escalationStage], чтобы их
 * можно было проверять тестом без рендеринга: ошибка в границе («на 20-й секунде
 * показали не то») глазами не ловится.
 */
enum class EscalationStage {
    /** Меньше 10 секунд: обычное «Подключение», тревожить нечем. */
    Normal,

    /** 10–20 секунд: добавляем пояснение, что сервер отвечает медленно. */
    SlowServer,

    /** Больше 20 секунд: нейтрально предлагаем альтернативу. */
    SuggestAlternative,

    /** Больше 30 секунд: это уже не «идёт подключение», а честная ошибка с выходом. */
    Failed,
}

/**
 * Пороги эскалации ожидания: 10 / 20 / 30 секунд.
 *
 * Границы полуинтервалов выбраны так, чтобы ровно 10 и ровно 20 секунд оставались
 * в предыдущей стадии: «10–20 секунд» — это пояснение о медленном сервере, а не
 * предложение сменить страну.
 *
 * Дольше 30 секунд — уже не процесс, а [EscalationStage.Failed]: продолжать
 * показывать «Подключение» значило бы врать, что шанс ещё есть.
 */
fun escalationStage(seconds: Int): EscalationStage = when {
    seconds > 30 -> EscalationStage.Failed
    seconds > 20 -> EscalationStage.SuggestAlternative
    seconds >= 10 -> EscalationStage.SlowServer
    else -> EscalationStage.Normal
}

/** Что показать на текущей стадии ожидания. Разметка берёт текст и тон отсюда. */
data class EscalationPresentation(
    val title: String,
    val detail: String,
    /** Пояснительная строка под заголовком. `null` — показывать нечего. */
    val notice: String?,
    /** Тон пояснения. Осмыслен только когда [notice] не `null`. */
    val noticeTone: Tone,
    /** Показывать ли выходы «Повторить» и «Выбрать другое». */
    val showExits: Boolean,
)

/**
 * Собирает подачу для стадии ожидания.
 *
 * Отдельно от Composable по той же причине, что `presentation()` в соседнем файле:
 * это решение «какой текст и какая тревога», и оно проверяется тестом.
 *
 * Тревога нарастает медленно и до последнего остаётся нейтральной: «сервер
 * отвечает медленно» и «доступны другие страны» — это пояснения, а не ошибки.
 * Янтарный и красный до 30 секунд кричали бы о проблеме там, где идёт нормальный,
 * просто небыстрый процесс.
 */
fun escalationPresentation(
    stage: EscalationStage,
    countryName: String,
    seconds: Int,
): EscalationPresentation = when (stage) {
    EscalationStage.Normal -> EscalationPresentation(
        title = "Подключение",
        detail = "$countryName · $seconds сек",
        notice = null,
        noticeTone = Tone.Neutral,
        showExits = false,
    )

    EscalationStage.SlowServer -> EscalationPresentation(
        title = "Подключение",
        detail = "$countryName · $seconds сек. Сервер отвечает медленно — возможно, перегружен.",
        notice = null,
        noticeTone = Tone.Neutral,
        showExits = false,
    )

    EscalationStage.SuggestAlternative -> EscalationPresentation(
        title = "Подключение",
        detail = "$countryName · $seconds сек. Сервер отвечает медленно — возможно, перегружен.",
        notice = "Турция и Финляндия доступны — они могут подключиться быстрее.",
        noticeTone = Tone.Neutral,
        showExits = false,
    )

    EscalationStage.Failed -> EscalationPresentation(
        title = "Долго не отвечает",
        detail = "$countryName · $seconds сек. Подключение не состоялось.",
        notice = null,
        noticeTone = Tone.Neutral,
        showExits = true,
    )
}

/**
 * Индикатор эскалации ожидания.
 *
 * Принимает секунды, сам определяет стадию через [escalationStage] и показывает
 * соответствующее состояние. Дольше 30 секунд даёт честную ошибку с выходом:
 * кнопку «Повторить» и ghost «Выбрать другое» — без них пользователь остаётся
 * ждать там, где ждать уже нечего.
 */
@Composable
fun WaitingEscalation(
    seconds: Int,
    countryName: String,
    onRetry: () -> Unit,
    onChooseOther: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stage = escalationStage(seconds)
    val presentation = escalationPresentation(stage, countryName, seconds)
    // Ошибка после 30 секунд — не «процесс», а состоявшийся отказ, поэтому
    // красный (как и у Failed в StatusPresentation), а не янтарный.
    val titleColor = if (stage == EscalationStage.Failed) VpnColors.Red else VpnColors.Amber

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(WAITING_ESCALATION_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            presentation.title,
            color = titleColor,
            fontSize = 25.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            presentation.detail,
            color = VpnColors.Ash,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, start = 10.dp, end = 10.dp),
        )
        if (presentation.notice != null) {
            Box(modifier = Modifier.padding(top = 16.dp)) {
                VpnNotice(
                    text = presentation.notice,
                    tone = presentation.noticeTone,
                )
            }
        }
        if (presentation.showExits) {
            VpnPrimaryButton(
                text = "Повторить",
                onClick = onRetry,
                modifier = Modifier.padding(top = 16.dp),
            )
            VpnGhostButton(
                text = "Выбрать другое",
                onClick = onChooseOther,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
