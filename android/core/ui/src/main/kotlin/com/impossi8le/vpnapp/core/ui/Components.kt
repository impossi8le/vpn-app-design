package com.impossi8le.vpnapp.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.unit.Dp

/**
 * Общие детали интерфейса, собранные по `docs/design/mockup.html`.
 *
 * Зачем отдельный файл: восемнадцать экранов повторяют одни и те же карточки,
 * строки, значки и предупреждения. Копия в каждом экране разошлась бы при первой
 * же правке макета, а расхождения в таких мелочах заметны пользователю раньше,
 * чем в текстах.
 *
 * Здесь только вид. Ни одно решение о состоянии не принимается: что показать и
 * каким цветом — решает `presentation()` в `feature/home`, где это проверяется
 * тестами.
 */

/** Высота интерактивных элементов. 44pt — минимум из доступности, не украшение. */
val MinTouchTarget = 44.dp

/**
 * Карточка.
 *
 * `border` вместо `Card` из Material: у макета рамка в полпикселя и свой радиус,
 * а Material навязывает и высоту тени, и собственные отступы, которые потом
 * приходится переопределять.
 */
@Composable
fun VpnCard(
    modifier: Modifier = Modifier,
    background: Color = VpnColors.Carbon,
    border: Color = VpnColors.Hairline,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(VpnRadii.Card.dp))
            .background(background)
            .border(0.5.dp, border, RoundedCornerShape(VpnRadii.Card.dp))
            .padding(15.dp),
    ) { content() }
}

/** Строка «ключ — значение» внутри карточки. */
@Composable
fun VpnRow(
    key: String,
    sub: String? = null,
    value: String? = null,
    valueColor: Color = VpnColors.Bone,
    valueMono: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(key, color = VpnColors.TextSecondary, fontSize = 14.sp)
            if (sub != null) {
                Text(
                    sub,
                    color = VpnColors.Ash,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else if (value != null) {
            Text(
                value,
                color = valueColor,
                fontSize = if (valueMono) 12.5.sp else 13.5.sp,
                fontFamily = if (valueMono) MonoFont else null,
            )
        }
    }
}

/**
 * Предупреждение.
 *
 * Три варианта по макету, и различие между ними смысловое:
 *  - [Tone.Danger] — реальная опасность (окно без защиты). Единственное место,
 *    где уместен красный;
 *  - [Tone.Warning] — внимание без опасности (эскалация ожидания);
 *  - [Tone.Neutral] — пояснение, действие пользователя не требуется.
 *
 * Именно поэтому это не «цветной прямоугольник с текстом»: выбор тона —
 * решение, и оно должно быть видно в месте вызова.
 */
@Composable
fun VpnNotice(
    text: String,
    tone: Tone = Tone.Neutral,
    icon: String? = null,
    testTag: String? = null,
) {
    val (bg, border, accent) = when (tone) {
        Tone.Danger -> Triple(
            VpnColors.Red.copy(alpha = 0.09f),
            VpnColors.Red.copy(alpha = 0.34f),
            VpnColors.Red,
        )

        Tone.Warning -> Triple(
            VpnColors.Amber.copy(alpha = 0.08f),
            VpnColors.Amber.copy(alpha = 0.30f),
            VpnColors.Amber,
        )

        Tone.Neutral -> Triple(VpnColors.Obsidian, VpnColors.Smoke, VpnColors.Ice)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(VpnRadii.Small.dp))
            .background(bg)
            .border(0.5.dp, border, RoundedCornerShape(VpnRadii.Small.dp))
            .padding(11.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Text(icon, color = accent, fontSize = 14.sp)
        }
        Text(text, color = VpnColors.Mist, fontSize = 12.5.sp, lineHeight = 19.sp)
    }
}

/** Тон предупреждения. Смысл тона описан у [VpnNotice]. */
enum class Tone { Danger, Warning, Neutral }

/**
 * Значок состояния конфига.
 *
 * Состояние различается и цветом, и текстом: только цветом — это дефект
 * доступности, найденный на ревью макета (дальтонизм).
 */
@Composable
fun VpnBadge(text: String, tone: BadgeTone) {
    val (bg, fg) = when (tone) {
        BadgeTone.Selected -> VpnColors.Ice.copy(alpha = 0.14f) to VpnColors.Ice
        BadgeTone.Active -> VpnColors.Green.copy(alpha = 0.14f) to VpnColors.Green
        BadgeTone.Dead -> VpnColors.Obsidian to VpnColors.Ash
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(text, color = fg, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** Тон значка. */
enum class BadgeTone { Selected, Active, Dead }

/**
 * Метка страны.
 *
 * Буквы, а не флаг-эмодзи: эмодзи рисуются по-разному на разных прошивках, а
 * буквенный код узнаётся всегда.
 */
@Composable
fun CountryMark(code: String, selected: Boolean = false, dead: Boolean = false) {
    val bg = when {
        selected -> VpnColors.Ice.copy(alpha = 0.12f)
        else -> VpnColors.Obsidian
    }
    val fg = when {
        selected -> VpnColors.Ice
        dead -> VpnColors.Ash
        else -> VpnColors.TextSecondary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(0.5.dp, if (selected) VpnColors.Ice.copy(alpha = 0.3f) else VpnColors.Smoke, RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(code, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Переключатель.
 *
 * Обёрнут в зону ≥44pt: у макета сам тумблер 28dp, и попасть в него пальцем
 * трудно — это была находка доступности.
 *
 * Включённое состояние показано и заливкой, и рамкой, а не только цветом.
 */
@Composable
fun VpnSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null,
) {
    Row(
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch,
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(15.dp))
                .background(if (checked) VpnColors.Ice else VpnColors.Smoke)
                .border(
                    0.5.dp,
                    if (checked) VpnColors.Ice else VpnColors.Edge,
                    RoundedCornerShape(15.dp),
                )
                .padding(horizontal = 3.dp, vertical = 3.dp),
        ) {
            Row {
                if (checked) Box(Modifier.size(20.dp))
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (checked) VpnColors.Void else VpnColors.TextSecondary)
                        .size(20.dp),
                )
            }
        }
    }
}

/** Метка раздела: приглушённая, с разрядкой, над группой карточек. */
@Composable
fun SectionLabel(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            color = VpnColors.Ash,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Text(
                action,
                color = VpnColors.Ice,
                fontSize = 12.5.sp,
                modifier = Modifier
                    .clickable(onClick = onAction)
                    .heightIn(min = MinTouchTarget)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/** Моноширинный шрифт для адресов, хешей и таймеров. */
val MonoFont = androidx.compose.ui.text.font.FontFamily.Monospace
/**
 * Основная кнопка.
 *
 * Белая, без цвета бренда: цвет на экране принадлежит состоянию, а не бренду.
 * Высота 50dp — из макета, а не «на глаз»: при ней подпись в одну строку
 * помещается на самой узкой поддерживаемой ширине.
 */
@Composable
fun VpnPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(VpnRadii.Button.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = VpnColors.Bone,
            contentColor = VpnColors.Void,
            disabledContainerColor = VpnColors.Bone.copy(alpha = 0.5f),
            disabledContentColor = VpnColors.Void.copy(alpha = 0.7f),
        ),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        Text(text = text, fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Второстепенная кнопка: прозрачный фон, рамка [VpnColors.Edge].
 *
 * Рамка не тоньше 0.5dp и цветом не темнее Edge: на ревью макета граница
 * ghost-кнопок давала контраст 1.59:1 при требуемых 3:1, и кнопка выглядела
 * неотличимо от фона.
 */
@Composable
fun VpnGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    height: Dp = 50.dp,
    borderColor: Color = VpnColors.Edge,
    contentColor: Color = VpnColors.Bone,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(VpnRadii.Button.dp),
        border = BorderStroke(0.5.dp, borderColor),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Верхняя панель экрана.
 *
 * Отдельный компонент, потому что «назад» в левом углу и заголовок по центру
 * повторяются на всех вложенных экранах, а несимметричные отступы в таких
 * местах заметны сразу.
 */
@Composable
fun VpnNavBar(
    title: String,
    onBack: (() -> Unit)? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    backTestTag: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .padding(top = 2.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Левая ячейка фиксированной ширины: иначе при отсутствии «назад»
        // заголовок сдвигается влево, и экраны выглядят по-разному.
        Box(modifier = Modifier.width(52.dp)) {
            if (onBack != null) {
                Text(
                    "‹ Назад",
                    color = VpnColors.Ice,
                    fontSize = 14.5.sp,
                    modifier = Modifier
                        .heightIn(min = MinTouchTarget)
                        .clickable(onClick = onBack)
                        .padding(vertical = 12.dp)
                        .then(if (backTestTag != null) Modifier.testTag(backTestTag) else Modifier),
                )
            }
        }
        Text(
            title,
            color = VpnColors.Bone,
            fontSize = 16.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Box(modifier = Modifier.width(52.dp)) {
            if (action != null && onAction != null) {
                Text(
                    action,
                    color = VpnColors.Ice,
                    fontSize = 13.5.sp,
                    modifier = Modifier
                        .heightIn(min = MinTouchTarget)
                        .clickable(onClick = onAction)
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}
