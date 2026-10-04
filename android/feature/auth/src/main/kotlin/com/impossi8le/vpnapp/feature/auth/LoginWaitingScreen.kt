package com.impossi8le.vpnapp.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.MonoFont
import com.impossi8le.vpnapp.core.ui.VpnColors

const val LOGIN_WAITING_TITLE_TAG = "login_waiting_title"
const val LOGIN_WAITING_COUNTDOWN_TAG = "login_waiting_countdown"
const val LOGIN_WAITING_DEMO_TAG = "login_waiting_demo"
const val LOGIN_WAITING_REOPEN_TAG = "login_waiting_reopen"

/**
 * Экран ожидания подтверждения входа (макет «1б. Проверяем вход»).
 *
 * Отсчёт приходит готовой строкой из состояния, а не считается внутри: таймер
 * должен быть общим с логикой (метка ставится до ухода в фон, см. AuthViewModel),
 * иначе он разойдётся при возврате из Telegram.
 *
 * Значок янтарный — идущий процесс. Это не ошибка и не опасность, поэтому ни
 * красного, ни зелёного здесь быть не может.
 */
@Composable
fun LoginWaitingScreen(
    remainingLabel: String,
    onReopenTelegram: () -> Unit,
    /**
     * Демонстрационный проход дальше, без подтверждения в Telegram.
     *
     * Нужен, потому что настоящий вход ждёт подтверждения в боте: без этого
     * прохода все экраны после входа недостижимы, и проверить их нельзя.
     * Кнопка явно помечена как демонстрационная и не выдаёт себя за вход;
     * статус защиты она не затрагивает.
     */
    onContinueDemo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VpnColors.Void)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        AuthMark(tint = VpnColors.Amber)

        Text(
            text = "Проверяем вход",
            color = VpnColors.Bone,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 31.sp,
            modifier = Modifier
                .padding(top = 24.dp)
                .testTag(LOGIN_WAITING_TITLE_TAG),
        )

        Text(
            text = buildAnnotatedString {
                append("Откройте Telegram — бот пришлёт кнопку ")
                withStyle(SpanStyle(color = VpnColors.Bone, fontWeight = FontWeight.Medium)) {
                    append("«Подтвердить вход»")
                }
                append(". Нажмите её, и мы продолжим сами.")
            },
            color = VpnColors.TextSecondary,
            fontSize = 14.sp,
            lineHeight = 22.sp,
            modifier = Modifier.padding(top = 11.dp),
        )

        LeftAccentBlock(
            accent = VpnColors.Amber,
            modifier = Modifier.padding(top = 20.dp),
        ) {
            Text(
                text = buildAnnotatedString {
                    append("Подтверждение действует 5 минут. Осталось ")
                    withStyle(SpanStyle(fontFamily = MonoFont)) { append(remainingLabel) }
                },
                color = VpnColors.TextSecondary,
                fontSize = 12.5.sp,
                lineHeight = 20.sp,
                modifier = Modifier.testTag(LOGIN_WAITING_COUNTDOWN_TAG),
            )
        }

        GhostButton(
            text = "Открыть Telegram ещё раз",
            onClick = onReopenTelegram,
            height = 46.dp,
            testTag = LOGIN_WAITING_REOPEN_TAG,
            modifier = Modifier.padding(top = 20.dp),
        )

        // Демонстрационный проход. Настоящий вход ждёт подтверждения в
        // Telegram, которого в проверочной сборке нет; без этого прохода
        // десять экранов после входа остаются недостижимыми и непроверенными.
        //
        // Кнопка названа прямо, а не замаскирована под «Продолжить»: выдать
        // демонстрацию за состоявшийся вход значило бы соврать о том, что
        // пользователь вошёл. Статус защиты это не затрагивает — он появляется
        // только из замера.
        GhostButton(
            text = "Демонстрация: показать экраны дальше",
            onClick = onContinueDemo,
            height = 46.dp,
            testTag = LOGIN_WAITING_DEMO_TAG,
            modifier = Modifier.padding(top = 9.dp),
        )
    }
}
