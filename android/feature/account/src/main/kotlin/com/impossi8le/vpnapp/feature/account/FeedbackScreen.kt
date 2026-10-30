package com.impossi8le.vpnapp.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnNavBar
import com.impossi8le.vpnapp.core.ui.VpnPrimaryButton

const val FEEDBACK_TEXT_TAG = "feedback_text"
const val FEEDBACK_SEND_TAG = "feedback_send"
const val FEEDBACK_BACK_TAG = "feedback_back"

/**
 * Потолок введённого текста.
 *
 * Совпадает с серверным (`MAX_FEEDBACK_CHARS = 4000`): режем в поле, чтобы
 * человек видел границу во время набора, а не получал отказ после отправки.
 * Сама отправка тоже обрезает — вставка из буфера и другие вызывающие не должны
 * упираться в `400`.
 */
const val FEEDBACK_MAX_CHARS = 4000

/**
 * Состояние экрана «Пожелания».
 *
 * [sent] отделено от [message]: успех не просто «нет ошибки», а конечное
 * состояние, в котором поле закрыто. Иначе после отправки текст можно было бы
 * править, а кнопка снова звала бы отправку.
 */
data class FeedbackScreenState(
    val text: String = "",
    val sending: Boolean = false,
    val sent: Boolean = false,
    val message: String? = null,
)

/**
 * Экран пожеланий и предложений.
 *
 * **Зачем отдельный экран, а не строка.** Пожелание — свободный текст на
 * несколько строк; в одну строку списка он не влезает, а поле ввода внутри
 * карточки аккаунта прокручивалось бы вместе со страницей и прыгало под пальцем.
 *
 * **Почему текст живёт снаружи.** Отправка идёт через тот же путь, что и
 * остальные действия: экран сообщает намерение, приложение выполняет и
 * возвращает состояние. Так экран не знает про сеть, а решение проверяется без
 * рендера.
 */
@Composable
fun FeedbackScreen(
    state: FeedbackScreenState,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
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
        VpnNavBar(title = "Пожелания", onBack = onBack, backTestTag = FEEDBACK_BACK_TAG)

        Text(
            text = "Что улучшить?",
            color = VpnColors.Bone,
            fontSize = 17.sp,
        )
        Text(
            text = "Расскажите, чего не хватает или что мешает. Сообщение уйдёт " +
                "разработчику вместе с версией приложения — без ваших данных и профиля.",
            color = VpnColors.Ash,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(top = 6.dp),
        )

        OutlinedTextField(
            value = state.text,
            onValueChange = { onTextChange(it.take(FEEDBACK_MAX_CHARS)) },
            enabled = !state.sending && !state.sent,
            minLines = 6,
            placeholder = {
                Text("Например: хочу видеть скорость соединения", color = VpnColors.Ash)
            },
            colors = feedbackFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .testTag(FEEDBACK_TEXT_TAG),
        )

        Text(
            text = "${state.text.length} / $FEEDBACK_MAX_CHARS",
            color = VpnColors.Ash,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
        )

        VpnPrimaryButton(
            text = when {
                state.sending -> "Отправляем…"
                state.sent -> "Отправлено"
                else -> "Отправить"
            },
            onClick = onSend,
            enabled = state.text.isNotBlank() && !state.sending && !state.sent,
            testTag = FEEDBACK_SEND_TAG,
            modifier = Modifier.padding(top = 14.dp),
        )

        // Итог показываем словами: успех и отказ выглядят по-разному, и цвет —
        // не единственный признак.
        state.message?.let { message ->
            Text(
                text = message,
                color = if (state.sent) VpnColors.Ice else VpnColors.Red,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * Цвета поля на тёмном фоне.
 *
 * Умолчание Material3 рисует текст почти чёрным на `VpnColors.Void`, и
 * введённое не видно. Те же токены, что на экране входа и в обходах.
 */
@Composable
private fun feedbackFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = VpnColors.Bone,
    unfocusedTextColor = VpnColors.Bone,
    disabledTextColor = VpnColors.Bone,
    cursorColor = VpnColors.Ice,
    focusedBorderColor = VpnColors.Ice,
    unfocusedBorderColor = VpnColors.Edge,
    focusedPlaceholderColor = VpnColors.Ash,
    unfocusedPlaceholderColor = VpnColors.Ash,
)