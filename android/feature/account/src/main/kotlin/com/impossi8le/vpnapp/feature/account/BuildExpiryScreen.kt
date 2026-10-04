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
import com.impossi8le.vpnapp.core.ui.Tone
import com.impossi8le.vpnapp.core.ui.VpnCard
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnNotice
import com.impossi8le.vpnapp.core.ui.VpnRow

const val BUILD_EXPIRY_BACK_TAG = "build_expiry_back"
const val BUILD_EXPIRY_WARNING_TAG = "build_expiry_warning"
const val BUILD_EXPIRY_HOW_TAG = "build_expiry_how"

/**
 * Сборка перестанет работать.
 *
 * Аналог TestFlight-предупреждения для Android: сборка живёт ограниченный срок
 * и после даты не запускается. Тон намеренно ледовый, а не тревожный — это
 * плановое событие, известное заранее, и вся забота в том, чтобы человек успел
 * обновиться. Красный здесь читался бы как сбой, которого нет.
 *
 * Экран не обещает автообновления: приложение не обновляет себя само, поэтому
 * текст говорит «обновите», а не «обновится».
 */
@Composable
fun BuildExpiryScreen(
    expiryDate: String,
    onBack: () -> Unit,
    onHowToUpdate: () -> Unit,
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
            title = "Сборка приложения",
            onBack = onBack,
            testTag = BUILD_EXPIRY_BACK_TAG,
        )

        VpnNotice(
            text = "Сборка приложения перестанет работать $expiryDate. " +
                "Обновите приложение по ссылке из бота — иначе оно не запустится.",
            tone = Tone.Neutral,
            icon = "↑",
            testTag = BUILD_EXPIRY_WARNING_TAG,
        )

        Text(
            text = "Обновление приходит от сервиса и не теряет ваши подключения: " +
                "после установки новой сборки они вернутся сами.",
            color = VpnColors.TextSecondary,
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 16.dp),
        )

        SectionLabel(text = "Дата")

        VpnCard {
            VpnRow(
                key = "Перестанет работать",
                sub = "Обновите по ссылке из бота",
                value = expiryDate,
                valueMono = true,
            )
        }

        LinkAction(
            text = "Как обновить ›",
            onClick = onHowToUpdate,
            testTag = BUILD_EXPIRY_HOW_TAG,
        )
    }
}
