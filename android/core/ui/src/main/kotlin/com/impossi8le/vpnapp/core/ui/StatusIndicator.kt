package com.impossi8le.vpnapp.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Индикатор состояния: концентрические кольца со щитом в центре.
 *
 * **Кольца неинтерактивны, и это решение, а не недоделка.** В первой версии
 * макета они выглядели нажимаемыми, и жест по ним ничего не делал — человек
 * пытался «включить VPN» тапом по картинке. Теперь это чистое отображение.
 *
 * Цвет колец повторяет смысл состояния и не несёт собственного значения:
 * [RingTone.Protected] зелёный — только после замера, [RingTone.Process] янтарный
 * — только идущий процесс, [RingTone.Danger] красный — реальная опасность,
 * [RingTone.Idle] нейтральный — «просто выключено», и это НЕ ошибка.
 */
@Composable
fun StatusRings(
    tone: RingTone,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    val (ringColor, coreBorder, shieldColor) = when (tone) {
        RingTone.Protected -> Triple(
            VpnColors.Green.copy(alpha = 0.30f),
            VpnColors.Green.copy(alpha = 0.45f),
            VpnColors.Green,
        )

        RingTone.Process -> Triple(
            VpnColors.Amber.copy(alpha = 0.24f),
            VpnColors.Amber.copy(alpha = 0.40f),
            VpnColors.Bone,
        )

        RingTone.Attention -> Triple(
            VpnColors.Hairline,
            VpnColors.Amber.copy(alpha = 0.40f),
            VpnColors.Amber,
        )

        RingTone.Danger -> Triple(
            VpnColors.Hairline,
            VpnColors.Red.copy(alpha = 0.42f),
            VpnColors.Red,
        )

        RingTone.Verifying -> Triple(
            VpnColors.Edge,
            VpnColors.Edge,
            VpnColors.Mist,
        )

        RingTone.Idle -> Triple(
            VpnColors.Hairline,
            VpnColors.Edge,
            VpnColors.Mist,
        )
    }

    Box(
        modifier = modifier
            .size(118.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(118.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            // Три кольца, как в макете: 118 / 92 / 68.
            listOf(59f, 46f, 34f).forEach { r ->
                drawCircle(
                    color = ringColor,
                    radius = r.dp.toPx(),
                    center = center,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
        // Ядро: круг с рамкой и щитом внутри.
        Box(
            modifier = Modifier
                .size(50.dp)
                .padding(0.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(50.dp)) {
                drawCircle(
                    color = VpnColors.Obsidian,
                    radius = size.minDimension / 2f,
                )
                drawCircle(
                    color = coreBorder,
                    radius = size.minDimension / 2f - 0.5.dp.toPx(),
                    style = Stroke(width = 1.dp.toPx()),
                )
                // Щит: контур, а не заливка — так он читается на любом фоне.
                val w = size.width
                val h = size.height
                val shield = Path().apply {
                    moveTo(w * 0.30f, h * 0.24f)
                    lineTo(w * 0.50f, h * 0.16f)
                    lineTo(w * 0.70f, h * 0.24f)
                    lineTo(w * 0.70f, h * 0.48f)
                    quadraticTo(w * 0.70f, h * 0.72f, w * 0.50f, h * 0.84f)
                    quadraticTo(w * 0.30f, h * 0.72f, w * 0.30f, h * 0.48f)
                    close()
                }
                drawPath(shield, color = shieldColor, style = Stroke(width = 1.7.dp.toPx()))
            }
        }
    }
}

/**
 * Тон колец.
 *
 * Шесть значений, а не четыре, и различие между [Verifying] и [Process]
 * существенное. В макете это два разных состояния:
 *  - [Process] — идёт подключение, кольца янтарные;
 *  - [Verifying] — туннель УЖЕ поднят, но защита не подтверждена. Кольца
 *    нейтральные, потому что процесс окончен, а результата ещё нет. Красить их
 *    янтарным значило бы сказать «идёт работа» там, где работа уже сделана, а
 *    зелёным — соврать, что проверка пройдена.
 *
 * [Attention] — отдельный случай: замер состоялся и НЕ подтвердил защиту.
 * Янтарный, не красный: соединение не разорвано, пользователь просто не защищён,
 * и об этом надо сказать спокойно, но внятно.
 */
enum class RingTone { Protected, Process, Verifying, Attention, Danger, Idle }

/**
 * Заголовок состояния с пояснением.
 *
 * Заголовок и пояснение приходят из `presentation()` в `feature/home` — здесь
 * только раскладка. Причина: решение «какой текст и цвет» проверяется тестом как
 * чистая функция, а не глазами по рендеру.
 */
@Composable
fun StatusHeadline(
    title: String,
    detail: String,
    accent: Color,
    modifier: Modifier = Modifier,
    titleTestTag: String? = null,
    detailTestTag: String? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            color = accent,
            fontSize = 25.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (titleTestTag != null) Modifier.testTag(titleTestTag) else Modifier),
        )
        Text(
            detail,
            color = VpnColors.Ash,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp, start = 10.dp, end = 10.dp)
                .then(if (detailTestTag != null) Modifier.testTag(detailTestTag) else Modifier),
        )
    }
}