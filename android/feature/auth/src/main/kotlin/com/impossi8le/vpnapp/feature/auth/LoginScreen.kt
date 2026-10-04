package com.impossi8le.vpnapp.feature.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.MonoFont
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnRadii

/** Теги: одна и та же строка в UI и в тестах, чтобы тест не искал по тексту. */
const val LOGIN_BUTTON_TAG = "login_button"
const val LOGIN_TITLE_TAG = "login_title"
const val LOGIN_PRIVACY_TAG = "login_privacy"
const val LOGIN_TERMS_TAG = "login_terms"

/**
 * Экран входа (макет «1. Вход»).
 *
 * Логики здесь нет и не должно быть: экран только показывает текст. Всё, что
 * имеет решение — что за шаг, какое раскрытие, — зафиксировано в разметке по
 * макету, потому что это редакторские формулировки, а не вычисления.
 *
 * Ссылки на политику стоят ДО кнопки входа: пользователь должен успеть прочитать
 * условия, а не согласиться вслепую после системного диалога.
 */
@Composable
fun LoginScreen(
    onLogin: () -> Unit,
    onPrivacyPolicy: () -> Unit,
    onTerms: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VpnColors.Void)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        AuthMark(tint = VpnColors.Bone)

        Text(
            text = "VPN для ваших\nприложений",
            color = VpnColors.Bone,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 31.sp,
            modifier = Modifier
                .padding(top = 24.dp)
                .testTag(LOGIN_TITLE_TAG),
        )

        Text(
            text = "Сайты и приложения, которые перестали открываться, снова заработают.",
            color = VpnColors.TextSecondary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(top = 11.dp),
        )

        Column(
            modifier = Modifier.padding(top = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Step(1, "Подписка в боте")
            Step(2, "Вход тем же Telegram — ", muted = "если уже оплатили, начните отсюда")
            Step(3, "Подключение в один тап")
        }

        // Раскрытие того, что видит оператор: не спрятано в политику, а стоит на
        // пути входа. Это осознанное решение макета, а не дисклеймер «для галочки».
        LeftAccentBlock(
            accent = VpnColors.Edge,
            modifier = Modifier.padding(top = 18.dp),
        ) {
            Text(
                text = buildAnnotatedString {
                    append("Трафик шифруется и идёт через ваш сервер. Нам видны ")
                    withStyle(SpanStyle(color = VpnColors.Mist, fontWeight = FontWeight.Medium)) {
                        append("Telegram ID")
                    }
                    append(" и список ваших подключений.")
                },
                color = VpnColors.TextSecondary,
                fontSize = 12.5.sp,
                lineHeight = 20.sp,
            )
        }

        PrimaryButton(
            text = "Войти тем же Telegram",
            onClick = onLogin,
            modifier = Modifier.padding(top = 18.dp),
            testTag = LOGIN_BUTTON_TAG,
        )

        Text(
            text = buildAnnotatedString {
                append("Откроется Telegram — бот пришлёт кнопку ")
                withStyle(SpanStyle(color = VpnColors.Bone, fontWeight = FontWeight.Medium)) {
                    append("«Подтвердить вход»")
                }
                append(".")
            },
            color = VpnColors.Ash,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Политика конфиденциальности",
                color = VpnColors.Ice,
                fontSize = 11.5.sp,
                fontFamily = MonoFont,
                modifier = Modifier
                    .clickable(onClick = onPrivacyPolicy)
                    .testTag(LOGIN_PRIVACY_TAG),
            )
            Text(" · ", color = VpnColors.Ash, fontSize = 11.5.sp)
            Text(
                text = "Условия",
                color = VpnColors.Ice,
                fontSize = 11.5.sp,
                modifier = Modifier
                    .clickable(onClick = onTerms)
                    .testTag(LOGIN_TERMS_TAG),
            )
        }
    }
}

/** Нумерованный шаг. [muted] — продолжение строки приглушённым цветом, как в макете. */
@Composable
internal fun Step(number: Int, text: String, muted: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(17.dp)
                .clip(CircleShape)
                .border(0.5.dp, VpnColors.Edge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$number",
                color = VpnColors.TextSecondary,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = buildAnnotatedString {
                append(text)
                if (muted != null) {
                    withStyle(SpanStyle(color = VpnColors.TextSecondary)) { append(muted) }
                }
            },
            color = VpnColors.Mist,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

/**
 * Блок с левой акцентной полосой (`.disc` в макете).
 *
 * `IntrinsicSize.Min` по высоте — иначе полоса либо короче текста, либо тянется на
 * весь экран. Высота берётся от содержимого.
 */
@Composable
internal fun LeftAccentBlock(
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Box(modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp)) {
            content()
        }
    }
}

/** Значок-щит в квадрате. Тот же силуэт, что в макете, но нарисован канвой. */
@Composable
internal fun AuthMark(tint: Color) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(15.dp))
            // Градиент макета (#1d2331 → #141a24) заменён плоским Obsidian: это
            // ближайший токен палитры, и он не вводит новый цвет в систему.
            .background(VpnColors.Obsidian)
            .border(0.5.dp, VpnColors.Hairline, RoundedCornerShape(15.dp)),
        contentAlignment = Alignment.Center,
    ) {
        ShieldGlyph(tint = tint, modifier = Modifier.size(25.dp))
    }
}

/**
 * Щит контуром.
 *
 * Рисуется канвой, а не эмодзи: эмодзи-щит выглядит по-разному на прошивках, а
 * контур повторяет макет один в один.
 */
@Composable
internal fun ShieldGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val u = size.minDimension / 24f
        val shield = Path().apply {
            moveTo(12f * u, 3f * u)
            lineTo(19f * u, 7f * u)
            lineTo(19f * u, 12f * u)
            cubicTo(19f * u, 16.2f * u, 16.1f * u, 19.6f * u, 12f * u, 20.6f * u)
            cubicTo(7.9f * u, 19.6f * u, 5f * u, 16.2f * u, 5f * u, 12f * u)
            lineTo(5f * u, 7f * u)
            close()
        }
        drawPath(shield, color = tint, style = Stroke(width = 1.7f * u))
    }
}

/** Главная кнопка: белая заливка, тёмный текст. Цвет принадлежит состоянию, не бренду. */
@Composable
internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(VpnRadii.Button.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = VpnColors.Bone,
            contentColor = VpnColors.Void,
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

/** Второстепенная кнопка: прозрачный фон, рамка Edge. [height] — 46dp для мелкой. */
@Composable
internal fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    height: Dp = 50.dp,
) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(VpnRadii.Button.dp),
        border = BorderStroke(0.5.dp, VpnColors.Edge),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = VpnColors.Bone),
        contentPadding = PaddingValues(0.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
