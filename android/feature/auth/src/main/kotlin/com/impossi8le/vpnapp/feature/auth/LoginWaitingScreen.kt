package com.impossi8le.vpnapp.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
const val LOGIN_WAITING_NONCE_TAG = "login_waiting_nonce"
const val LOGIN_WAITING_SUBMIT_TAG = "login_waiting_submit"

/**
 * Экран ожидания подтверждения входа (макет «1б. Проверяем вход»).
 *
 * Отсчёт приходит готовой строкой из состояния, а не считается внутри: таймер
 * должен быть общим с логикой (метка ставится до ухода в фон, см. AuthViewModel),
 * иначе он разойдётся при возврате из Telegram.
 *
 * **Поле кода — не украшение, а защита входа.** Бот показывает шестизначный код,
 * и пользователь вводит его здесь; сервер подтверждает сессию только по нему.
 * Без этого шага вход завершался бы сам собой, и подтверждение в боте переставало
 * бы что-либо удостоверять. Поэтому поле обязательно, а кнопка без кода не
 * отправляет запрос (проверка пустого кода — в AuthViewModel).
 *
 * Значок янтарный — идущий процесс. Это не ошибка и не опасность, поэтому ни
 * красного, ни зелёного здесь быть не может.
 */
@Composable
fun LoginWaitingScreen(
    remainingLabel: String,
    signingIn: Boolean,
    errorText: String?,
    onSubmitNonce: (String) -> Unit,
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
    /**
     * Показывать ли демонстрационный проход.
     *
     * Пока по умолчанию `true` — это текущее поведение сборки для проверок.
     * Гейт по debug-сборке (`BuildConfig.DEBUG`) ставит Task 11: в release
     * демонстрационной кнопки быть не должно.
     */
    showDemoButton: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Ввод кода живёт на экране: состоянием логики он не является, наружу уходит
    // только готовый код при подтверждении.
    var nonce by remember { mutableStateOf("") }

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
                append("Откройте Telegram — бот пришлёт ")
                withStyle(SpanStyle(color = VpnColors.Bone, fontWeight = FontWeight.Medium)) {
                    append("код подтверждения")
                }
                append(". Введите его здесь.")
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

        // Поле кода. Оставляем только цифры и не длиннее шести: код всегда
        // шестизначный, и буква в нём — заведомо неверный ввод, который лучше не
        // отправлять на сервер вовсе.
        OutlinedTextField(
            value = nonce,
            onValueChange = { nonce = it.filter(Char::isDigit).take(6) },
            singleLine = true,
            label = { Text("Код из бота") },
            isError = errorText != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp)
                .testTag(LOGIN_WAITING_NONCE_TAG),
        )

        if (errorText != null) {
            Text(
                text = errorText,
                color = VpnColors.Amber,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        GhostButton(
            text = if (signingIn) "Проверяем…" else "Подтвердить",
            onClick = { onSubmitNonce(nonce) },
            height = 46.dp,
            testTag = LOGIN_WAITING_SUBMIT_TAG,
            modifier = Modifier.padding(top = 12.dp),
        )

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
        //
        // Кнопка есть только при [showDemoButton]: [MainActivity] ставит его в
        // `BuildConfig.DEBUG`, поэтому в release демонстрационного прохода нет.
        if (showDemoButton) {
            GhostButton(
                text = "Демонстрация: показать экраны дальше",
                onClick = onContinueDemo,
                height = 46.dp,
                testTag = LOGIN_WAITING_DEMO_TAG,
                modifier = Modifier.padding(top = 9.dp),
            )
        }
    }
}
