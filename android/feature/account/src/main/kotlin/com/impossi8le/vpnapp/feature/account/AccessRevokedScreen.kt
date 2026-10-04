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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnRadii

const val ACCESS_REVOKED_TITLE_TAG = "access_revoked_title"
const val ACCESS_REVOKED_SUPPORT_TAG = "access_revoked_support"
const val ACCESS_REVOKED_REFRESH_TAG = "access_revoked_refresh"
const val ACCESS_REVOKED_SWITCH_TAG = "access_revoked_switch"

/**
 * Экран «Доступ к стране закрыт» (макет «14. Доступ отозван»).
 *
 * Ключевая фраза — «Это не сбой сети»: раньше отзыв выглядел как «сервер не
 * отвечает», и человек бесконечно повторял попытку, которая не могла сработать.
 * Отзыв — решение сервера, само не вернётся, поэтому путь один: поддержка или
 * обновление данных, а не «подключиться ещё раз».
 *
 * Значок нейтральный, а не красный: доступ закрыт сервисом, но это не аварийная
 * опасность. Красный зарезервирован под окно без защиты.
 */
@Composable
fun AccessRevokedScreen(
    onContactSupport: () -> Unit,
    onRefreshAccess: () -> Unit,
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
            RevokedMark()

            Text(
                text = "Доступ к стране закрыт",
                color = VpnColors.Bone,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 27.sp,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .testTag(ACCESS_REVOKED_TITLE_TAG),
            )

            Text(
                text = buildAnnotatedString {
                    append("Эта страна больше недоступна. ")
                    withStyle(SpanStyle(color = VpnColors.TextSecondary)) {
                        append("Это не сбой сети.")
                    }
                },
                color = VpnColors.TextSecondary,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(top = 11.dp),
            )

            OutlinedButton(
                onClick = onContactSupport,
                shape = RoundedCornerShape(VpnRadii.Button.dp),
                border = BorderStroke(0.5.dp, VpnColors.Edge),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = VpnColors.Bone),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(46.dp)
                    .testTag(ACCESS_REVOKED_SUPPORT_TAG),
            ) {
                Text("Написать в поддержку", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onRefreshAccess,
            shape = RoundedCornerShape(VpnRadii.Button.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = VpnColors.Bone,
                contentColor = VpnColors.Void,
            ),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag(ACCESS_REVOKED_REFRESH_TAG),
        ) {
            Text("Обновить доступ", fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
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
                .testTag(ACCESS_REVOKED_SWITCH_TAG),
        ) {
            Text("Войти другим Telegram", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Значок-щит в нейтральном замолчавшем тоне: контур без акцента. */
@Composable
private fun RevokedMark() {
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
            drawPath(shield, color = VpnColors.TextSecondary, style = Stroke(width = 1.7f * u))
        }
    }
}
