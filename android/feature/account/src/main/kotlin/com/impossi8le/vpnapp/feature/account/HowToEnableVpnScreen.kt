package com.impossi8le.vpnapp.feature.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.MinTouchTarget
import com.impossi8le.vpnapp.core.ui.Tone
import com.impossi8le.vpnapp.core.ui.VpnCard
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnNotice
import com.impossi8le.vpnapp.core.ui.VpnRadii
import com.impossi8le.vpnapp.core.ui.VpnRow

const val HOW_TO_BACK_TAG = "how_to_back"
const val HOW_TO_TITLE_TAG = "how_to_title"
const val HOW_TO_RECHECK_TAG = "how_to_recheck"

/**
 * Экран «Как включить VPN» (макет «16. Инструкция по разрешению»).
 *
 * Свой экран вместо скрытого перехода в системные настройки: схема вида
 * `App-Prefs` рискованна на ревью стора и ломается между версиями. Здесь вместо
 * неё — статичная, читаемая инструкция и кнопка, которая просто перечитывает
 * состояние разрешения.
 *
 * Предупреждение нейтральное: нет разрешения — это не ошибка пользователя, а
 * известный путь, который надо пройти ногами по шагам.
 */
@Composable
fun HowToEnableVpnScreen(
    onBack: () -> Unit,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VpnColors.Void)
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "‹ Назад",
                color = VpnColors.Ice,
                fontSize = 14.5.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .testTag(HOW_TO_BACK_TAG),
            )
            Text(
                text = "Как включить VPN",
                color = VpnColors.Bone,
                fontSize = 16.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .testTag(HOW_TO_TITLE_TAG),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            // Пустой блок той же ширины, что «‹ Назад», чтобы заголовок остался
            // ровно по центру, а не сместился из-за левой кнопки.
            Spacer(Modifier.width(48.dp))
        }

        Text(
            text = "Этот запрос система показывает только один раз. " +
                "Если вы отказали — включите вручную, по шагам.",
            color = VpnColors.TextSecondary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        VpnCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                StepRow("1. Откройте Настройки", "Приложение «Настройки» на телефоне")
                StepDivider()
                StepRow("2. Раздел", "«VPN и управление устройством»")
                StepDivider()
                StepRow("3. Строка VPN", "Нажмите «VPN» внутри раздела")
                StepDivider()
                StepRow("4. Включите переключатель", "Для этого приложения")
            }
        }

        Box(modifier = Modifier.padding(top = 12.dp)) {
            VpnNotice(
                text = "Название может немного отличаться в вашей версии iOS. " +
                    "Ищите слово VPN.",
                tone = Tone.Neutral,
            )
        }

        Spacer(Modifier.weight(1f))

        OutlinedButton(
            onClick = onRecheck,
            shape = RoundedCornerShape(VpnRadii.Button.dp),
            border = BorderStroke(0.5.dp, VpnColors.Edge),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = VpnColors.Bone),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag(HOW_TO_RECHECK_TAG),
        ) {
            Text("Проверить снова", fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** Шаг инструкции: номер и описание. Тот же вид, что у строк в аккаунте. */
@Composable
private fun StepRow(title: String, sub: String) {
    VpnRow(key = title, sub = sub)
}

/** Разделитель между шагами: волосяная линия, как между строками в макете. */
@Composable
private fun StepDivider() {
    HorizontalDivider(color = VpnColors.Hairline, thickness = 0.5.dp)
}
