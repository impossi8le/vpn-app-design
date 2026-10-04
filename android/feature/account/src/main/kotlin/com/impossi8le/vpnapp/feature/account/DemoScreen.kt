package com.impossi8le.vpnapp.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.BadgeTone
import com.impossi8le.vpnapp.core.ui.CountryMark
import com.impossi8le.vpnapp.core.ui.MonoFont
import com.impossi8le.vpnapp.core.ui.SectionLabel
import com.impossi8le.vpnapp.core.ui.Tone
import com.impossi8le.vpnapp.core.ui.VpnBadge
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnNotice
import com.impossi8le.vpnapp.core.ui.VpnRadii

const val DEMO_BANNER_TAG = "demo_banner"
const val DEMO_STATUS_TAG = "demo_status"
const val DEMO_ACTION_TAG = "demo_action"

/** Тег строки включает код страны: тесту нужно нажимать конкретный демо-конфиг. */
fun demoConfigTag(code: String) = "demo_config_$code"

/** Демо-подключение. Названия стран вымышленные и повторяют макет дословно. */
data class DemoConfig(
    val code: String,
    val name: String,
    val until: String,
)

/**
 * Демо-режим (§18 макета).
 *
 * Зачем отдельный экран: у ревьюера нет подписки, и без демонстрации он не
 * пройдёт дальше первого экрана — отклонит вслепую. Поэтому здесь показан весь
 * интерфейс, но подписано прямо, что данные вымышленные.
 *
 * Жёсткое правило: демо НИКОГДА не показывает «защищено» как факт. Индикатор
 * состояния нейтральный, кнопка «Подключить» полупрозрачная и заблокирована —
 * экран не должен ни на секунду выглядеть как поднятый туннель.
 */
@Composable
fun DemoScreen(
    configs: List<DemoConfig>,
    selectedCode: String?,
    onSelect: (DemoConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VpnColors.Void)
            .verticalScroll(rememberScrollState()),
    ) {
        DemoStatusBar()

        Column(modifier = Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
            DemoNavBar()

            VpnNotice(
                text = "Демо-режим. Данные вымышленные. Туннель не поднимается — " +
                    "интерфейс показан для проверки.",
                tone = Tone.Warning,
                icon = "◆",
                testTag = DEMO_BANNER_TAG,
            )

            DemoIndicator()

            SectionLabel(text = "Подключения")

            configs.forEach { config ->
                DemoConfigRow(
                    config = config,
                    selected = config.code == selectedCode,
                    onSelect = onSelect,
                )
            }

            // Кнопка заблокирована: в демо туннель не поднимается, и активная
            // кнопка обещала бы действие, которого не будет.
            Button(
                onClick = {},
                enabled = false,
                shape = RoundedCornerShape(VpnRadii.Button.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = VpnColors.Bone.copy(alpha = 0.5f),
                    disabledContentColor = VpnColors.Void.copy(alpha = 0.5f),
                ),
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag(DEMO_ACTION_TAG),
            ) {
                Text(text = "Подключить", fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
            }

            Text(
                text = "В демо-режиме туннель не поднимается",
                color = VpnColors.Ash,
                fontSize = 11.5.sp,
                lineHeight = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 9.dp),
            )
        }
    }
}

/**
 * Полоса статус-бара с меткой DEMO.
 *
 * Метка стоит там, где система показывает состояние связи: скриншот демо-экрана
 * без неё невозможно отличить от настоящего подключения — а это ровно тот
 * ложный «защищено», которого демо не должно допускать.
 */
@Composable
private fun DemoStatusBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(50.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "DEMO",
            color = VpnColors.Amber,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = MonoFont,
        )
    }
}

@Composable
private fun DemoNavBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Подключение",
            color = VpnColors.Bone,
            fontSize = 16.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Индикатор состояния. Нейтральный: в демо защита не подтверждена и не будет. */
@Composable
private fun DemoIndicator() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Не подключено",
            color = VpnColors.TextSecondary,
            fontSize = 25.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag(DEMO_STATUS_TAG),
        )
        Text(
            text = "Демонстрация интерфейса",
            color = VpnColors.Ash,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun DemoConfigRow(
    config: DemoConfig,
    selected: Boolean,
    onSelect: (DemoConfig) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 7.dp)
            .background(
                if (selected) VpnColors.Obsidian else VpnColors.Carbon,
                RoundedCornerShape(VpnRadii.Card.dp),
            )
            .border(
                0.5.dp,
                if (selected) VpnColors.Ice.copy(alpha = 0.5f) else VpnColors.Hairline,
                RoundedCornerShape(VpnRadii.Card.dp),
            )
            .clickable { onSelect(config) }
            .padding(11.dp)
            .testTag(demoConfigTag(config.code)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        CountryMark(code = config.code, selected = selected)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = config.name,
                color = VpnColors.Bone,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Row {
                Text("до ", color = VpnColors.Ash, fontSize = 12.sp)
                Text(
                    text = config.until,
                    color = VpnColors.TextSecondary,
                    fontSize = 11.5.sp,
                    fontFamily = MonoFont,
                )
                Text(" · демо-данные", color = VpnColors.Ash, fontSize = 12.sp)
            }
        }

        if (selected) {
            VpnBadge(text = "Выбрано", tone = BadgeTone.Selected)
        }
    }
}
