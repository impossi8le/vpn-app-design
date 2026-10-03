package com.impossi8le.vpnapp.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnRadii
import com.impossi8le.vpnapp.domain.model.ConnectionStatus

/** Тег кнопки: один и тот же в UI и в тестах, чтобы тест не искал по тексту. */
const val HOME_ACTION_TAG = "home_action"
const val HOME_TITLE_TAG = "home_title"
const val HOME_DETAIL_TAG = "home_detail"

/**
 * Главный экран.
 *
 * Разметка намеренно тонкая: весь выбор текста и цвета сделан в
 * [presentation], которая проверяется тестами. Здесь остаётся только показать
 * результат. Причина: дефект ложного зелёного живёт в решении «что показать», а
 * не в раскладке, и проверять его через рендеринг дороже и позже.
 */
@Composable
fun HomeScreen(
    status: ConnectionStatus,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = status.presentation()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VpnColors.Void)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = presentation.title,
            color = presentation.accent,
            fontSize = 28.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag(HOME_TITLE_TAG),
        )

        Text(
            text = presentation.detail,
            color = VpnColors.TextSecondary,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(top = 8.dp)
                .testTag(HOME_DETAIL_TAG),
        )

        Button(
            onClick = { if (status.isProtected) onDisconnect() else onConnect() },
            shape = RoundedCornerShape(VpnRadii.Button.dp),
            colors = ButtonDefaults.buttonColors(
                // Основная кнопка белая: у бренда нет своего цвета, и это
                // осознанно — цвет на экране принадлежит состоянию, а не бренду.
                containerColor = VpnColors.Bone,
                contentColor = VpnColors.Void,
            ),
            modifier = Modifier
                .padding(top = 40.dp)
                .fillMaxWidth()
                .testTag(HOME_ACTION_TAG),
        ) {
            Text(text = if (status.isProtected) "Отключить" else "Подключить")
        }
    }
}
