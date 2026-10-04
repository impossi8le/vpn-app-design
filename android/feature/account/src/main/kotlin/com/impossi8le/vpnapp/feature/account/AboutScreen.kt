package com.impossi8le.vpnapp.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.SectionLabel
import com.impossi8le.vpnapp.core.ui.VpnCard
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnRow

const val ABOUT_BACK_TAG = "about_back"
const val ABOUT_PRIVACY_TAG = "about_privacy"
const val ABOUT_TERMS_TAG = "about_terms"

/**
 * О сервисе.
 *
 * Макет эту страницу не рисует — она не попала в прогон, поэтому здесь только
 * то, что обязано быть доступно из приложения и не является выдумкой продукта:
 * версия, назначение в одну фразу и правовые ссылки. Тексты о «безопасности» и
 * «анонимности» сознательно не добавлены: без замера это было бы обещанием
 * защиты, которой нет, — ровно тот дефект, что вычищался из макета.
 */
@Composable
fun AboutScreen(
    appVersion: String,
    onBack: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
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
        AccountNavBar(title = "О сервисе", onBack = onBack, testTag = ABOUT_BACK_TAG)

        Text(
            text = "VPN-клиент для доступа к сайтам и приложениям, которые перестали " +
                "открываться. Подписка оформляется в Telegram-боте сервиса, " +
                "приложение только поднимает соединение.",
            color = VpnColors.TextSecondary,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )

        SectionLabel(text = "Сборка")

        VpnCard {
            VpnRow(
                key = "Версия приложения",
                value = appVersion,
                valueMono = true,
            )
        }

        SectionLabel(text = "Документы")

        VpnCard {
            Column {
                VpnRow(
                    key = "Политика конфиденциальности",
                    trailing = { LinkAction("Открыть ›", onOpenPrivacy, ABOUT_PRIVACY_TAG) },
                )
                VpnRow(
                    key = "Условия использования",
                    trailing = { LinkAction("Открыть ›", onOpenTerms, ABOUT_TERMS_TAG) },
                )
            }
        }
    }
}
