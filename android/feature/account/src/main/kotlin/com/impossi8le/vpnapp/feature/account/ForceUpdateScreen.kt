package com.impossi8le.vpnapp.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.SectionLabel
import com.impossi8le.vpnapp.core.ui.Tone
import com.impossi8le.vpnapp.core.ui.UpdateUiState
import com.impossi8le.vpnapp.core.ui.VpnCard
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnNotice
import com.impossi8le.vpnapp.core.ui.VpnPrimaryButton
import com.impossi8le.vpnapp.core.ui.VpnRow

const val FORCE_UPDATE_TAG = "force_update"
const val FORCE_UPDATE_RECHECK_TAG = "force_update_recheck"

/**
 * Экран, который нельзя обойти: сборка ниже поддерживаемой.
 *
 * Нужен, когда версия клиента разошлась с сервером настолько, что старая уже не
 * работает. Показывается вместо рабочего экрана, а не поверх него, — иначе
 * пользователь упёрся бы в кнопку, которая ничего не делает.
 *
 * Тексты не обещают несуществующих гарантий: сказано ровно то, что известно —
 * версия ниже поддерживаемой и её нужно обновить. Причины (закрытая дыра,
 * сменившийся протокол) не выдумываются.
 */
@Composable
fun ForceUpdateScreen(
    installedVersionName: String,
    update: UpdateUiState.Available,
    updateProgress: Int?,
    updateMessage: String?,
    onDownloadUpdate: () -> Unit,
    onCheckUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VpnColors.Void)
            .padding(horizontal = 18.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Нужно обновить приложение",
            color = VpnColors.Bone,
            fontSize = 22.sp,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Эта версия больше не поддерживается. Обновитесь, чтобы продолжить пользоваться подключением.",
            color = VpnColors.TextSecondary,
            fontSize = 14.sp,
        )

        Spacer(modifier = Modifier.height(18.dp))

        VpnCard {
            Column {
                VpnRow(key = "Установлена", value = installedVersionName, valueMono = true)
                VpnRow(
                    key = "Требуется",
                    value = "1.0.${update.versionCode}",
                    valueMono = true,
                )
            }
        }

        if (updateProgress != null) {
            Spacer(modifier = Modifier.height(14.dp))
            VpnRow(
                key = "Скачивание обновления",
                value = "$updateProgress%",
                valueMono = true,
            )
        }

        // Причина отказа — рядом с кнопкой, а не вместо неё: повторить попытку
        // пользователь должен мочь, не перезапуская приложение.
        if (updateMessage != null && updateProgress == null) {
            Spacer(modifier = Modifier.height(14.dp))
            VpnNotice(text = updateMessage, tone = Tone.Warning, testTag = FORCE_UPDATE_TAG)
        }

        Spacer(modifier = Modifier.height(18.dp))

        SectionLabel(text = "Обновление")

        VpnPrimaryButton(
            text = if (updateProgress != null) "Скачивание…" else "Обновить",
            onClick = onDownloadUpdate,
            testTag = FORCE_UPDATE_TAG,
        )

        Spacer(modifier = Modifier.height(10.dp))

        // На случай, если пользователь обновился другим способом (через бота,
        // из релиза) — проверить заново, не перезапуская приложение.
        LinkAction("Я обновился — проверить снова ›", onCheckUpdate, FORCE_UPDATE_RECHECK_TAG)
    }
}
