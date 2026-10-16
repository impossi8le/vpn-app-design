package com.impossi8le.vpnapp.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.SectionLabel
import com.impossi8le.vpnapp.core.ui.Tone
import com.impossi8le.vpnapp.core.ui.VpnCard
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnNotice
import com.impossi8le.vpnapp.core.ui.VpnRow

const val LICENSES_BACK_TAG = "licenses_back"
const val LICENSES_ENGINE_TEXT_TAG = "licenses_engine_text"

/**
 * Экран «Лицензии сторонних компонентов».
 *
 * Зачем отдельный экран. В приложение статически влинковано ядро OpenVPN 3 под
 * MPL 2.0 и его зависимости, и уведомление о них обязано быть показано — этого
 * не было нигде ни в интерфейсе, ни в файлах проекта (issue #24). Строка в
 * списке для этого не годится: уведомление ядра — многострочный текст, он не
 * помещается в одну строку рядом с ключом.
 *
 * **Про лицензии, а не про защиту.** Экран не обещает ни безопасности, ни
 * анонимности: он называет сторонний код и его лицензии. Список — данные
 * ([THIRD_PARTY_COMPONENTS]), поэтому проверяется тестом без устройства.
 *
 * [engine] приходит готовым: экран не ходит в ядро сам. Текст уведомления ядро
 * отдаёт через `copyright()`, а вызов может провалиться (нет библиотеки) — тогда
 * сюда придёт [EngineLicenseNotice.Unavailable], и мы честно покажем причину, а
 * не выдуманную лицензию.
 */
@Composable
fun ThirdPartyLicensesScreen(
    engine: EngineLicenseNotice,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VpnColors.Void)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = 8.dp, bottom = 24.dp),
    ) {
        AccountNavBar(
            title = "Лицензии сторонних компонентов",
            onBack = onBack,
            testTag = LICENSES_BACK_TAG,
        )

        Text(
            text = "Приложение использует сторонний код. Ниже — названия " +
                "компонентов и их лицензии.",
            color = VpnColors.TextSecondary,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )

        SectionLabel(text = "Ядро")

        VpnCard {
            Column {
                VpnRow(
                    key = ENGINE_COMPONENT_NAME,
                    sub = ENGINE_COMPONENT_SUMMARY,
                    value = ENGINE_COMPONENT_LICENSE,
                )
                // Текст ядра — ДОПОЛНИТЕЛЬНО к имени компонента, а не вместо него:
                // при недоступном ядре строка выше всё равно называет лицензию
                // из наших данных, а здесь честно видно, что текст не получен.
                when (engine) {
                    is EngineLicenseNotice.Text -> Text(
                        text = engine.text,
                        color = VpnColors.Mist,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .testTag(LICENSES_ENGINE_TEXT_TAG),
                    )

                    is EngineLicenseNotice.Unavailable -> Box(
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        VpnNotice(text = engine.reason, tone = Tone.Neutral)
                    }
                }
            }
        }

        SectionLabel(text = "Зависимости ядра")

        VpnCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                THIRD_PARTY_COMPONENTS.forEachIndexed { index, component ->
                    if (index > 0) {
                        HorizontalDivider(color = VpnColors.Hairline, thickness = 0.5.dp)
                    }
                    VpnRow(
                        key = component.name,
                        sub = component.summary,
                        value = component.license,
                    )
                }
            }
        }

        Box(modifier = Modifier.padding(top = 12.dp)) {
            VpnNotice(text = LICENSES_FOOTNOTE, tone = Tone.Neutral)
        }
    }
}
