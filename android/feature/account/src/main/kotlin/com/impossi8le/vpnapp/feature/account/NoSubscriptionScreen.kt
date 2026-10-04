package com.impossi8le.vpnapp.feature.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.Tone
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnNotice
import com.impossi8le.vpnapp.core.ui.VpnRadii

const val NO_SUBSCRIPTION_TITLE_TAG = "no_subscription_title"
const val NO_SUBSCRIPTION_REFRESH_TAG = "no_subscription_refresh"
const val NO_SUBSCRIPTION_SWITCH_TAG = "no_subscription_switch"

/**
 * Экран «Этот Telegram не привязан к подписке» (макет «13. Подписки нет»).
 *
 * Серое вместо красного — это не мелочь: отсутствие подписки не ошибка и не
 * опасность, а состояние. Красный заставил бы человека думать, что что-то
 * сломалось у него, и искать поломку вместо оплаты.
 *
 * Главная кнопка обновляет данные, а не продаёт: призыв к покупке в интерфейсе
 * запрещён (анти-стиринг 3.1.1), о продлении сообщает только нейтральный текст.
 */
@Composable
fun NoSubscriptionScreen(
    onRefresh: () -> Unit,
    onSwitchTelegram: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VpnColors.Void)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.Center,
        ) {
            AccountMark(tint = VpnColors.TextSecondary)

            Text(
                text = "Этот Telegram\nне привязан к подписке",
                color = VpnColors.Bone,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 27.sp,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .testTag(NO_SUBSCRIPTION_TITLE_TAG),
            )

            Text(
                text = "Если вы только что оплатили — подождите пару минут и обновите. " +
                    "Если ещё нет — подписка оформляется в Telegram-боте сервиса.",
                color = VpnColors.TextSecondary,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(top = 11.dp),
            )

            Box(modifier = Modifier.padding(top = 16.dp)) {
                VpnNotice(
                    // Neutral, не Warning: подсказка про другой аккаунт — пояснение, а
                    // не требование действовать. Янтарный здесь читался бы как тревога.
                    text = "Проверьте аккаунт. Оплата могла быть с другого Telegram — " +
                        "войти нужно тем же, с которого покупали.",
                    tone = Tone.Neutral,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onRefresh,
            shape = RoundedCornerShape(VpnRadii.Button.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = VpnColors.Bone,
                contentColor = VpnColors.Void,
            ),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag(NO_SUBSCRIPTION_REFRESH_TAG),
        ) {
            Text("Обновить", fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
        }

        OutlinedButton(
            onClick = onSwitchTelegram,
            shape = RoundedCornerShape(VpnRadii.Button.dp),
            border = BorderStroke(0.5.dp, VpnColors.Edge),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = VpnColors.Bone),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(46.dp)
                .testTag(NO_SUBSCRIPTION_SWITCH_TAG),
        ) {
            Text("Войти другим Telegram", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Значок-щит в квадрате: тот же силуэт, что на экране входа. */
@Composable
private fun AccountMark(tint: Color) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(VpnColors.Obsidian)
            .border(0.5.dp, VpnColors.Hairline, RoundedCornerShape(15.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(25.dp)) {
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
}
