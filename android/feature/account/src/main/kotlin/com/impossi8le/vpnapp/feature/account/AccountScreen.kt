package com.impossi8le.vpnapp.feature.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.MinTouchTarget
import com.impossi8le.vpnapp.core.ui.MonoFont
import com.impossi8le.vpnapp.core.ui.SectionLabel
import com.impossi8le.vpnapp.core.ui.VpnCard
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnRadii
import com.impossi8le.vpnapp.core.ui.VpnRow
import com.impossi8le.vpnapp.core.ui.VpnSwitch

const val ACCOUNT_BACK_TAG = "account_back"
const val ACCOUNT_REVEAL_ID_TAG = "account_reveal_id"
const val ACCOUNT_CONFIRM_SWITCH_TAG = "account_confirm_switch"
const val ACCOUNT_SUPPORT_CHAT_TAG = "account_support_chat"
const val ACCOUNT_SEND_DIAGNOSTICS_TAG = "account_send_diagnostics"
const val ACCOUNT_ABOUT_TAG = "account_about"
const val ACCOUNT_DELETE_TAG = "account_delete"
const val ACCOUNT_LOGOUT_TAG = "account_logout"

/**
 * Маска вместо номера.
 *
 * Живёт в разметке, а не в модели: это чисто визуальное решение, и оно должно
 * оставаться рядом с местом, где показывается. Полный ID — только по кнопке:
 * скриншот в поддержку не должен отдавать профиль аккаунта.
 */
private const val MASKED_TELEGRAM_ID = "••••••••"

/**
 * Состояние экрана аккаунта.
 *
 * Плоские поля, а не вложенные структуры: экран только показывает их, решений
 * на их основе не принимает. Любая логика (что считать «истекло», какую дату
 * форматировать) остаётся во ViewModel, иначе её не проверить тестом без рендера.
 */
data class AccountScreenState(
    val telegramId: String,
    val telegramIdRevealed: Boolean,
    val activeConnections: Int,
    val totalConnections: Int,
    val expiredConnections: Int,
    val buildExpiryDate: String,
    val subscriptionUntil: String,
    val confirmCountrySwitch: Boolean,
)

/**
 * Экран аккаунта (§11 макета).
 *
 * Порядок блоков отражает частоту обращения: сначала данные, потом настройки
 * подключения, потом поддержка, и только в самом низу — необратимые действия.
 * Выход убран под разделитель «Опасное действие», хотя сам по себе не опасен:
 * красный контур достаётся только удалению, а выход — нейтральная ghost-кнопка.
 * Иначе уборка карточки выглядела бы так же, как рабочая кнопка.
 */
@Composable
fun AccountScreen(
    state: AccountScreenState,
    appVersion: String,
    onBack: () -> Unit,
    onToggleTelegramId: () -> Unit,
    onConfirmCountrySwitchChange: (Boolean) -> Unit,
    onSupportChat: () -> Unit,
    onSendDiagnostics: () -> Unit,
    onOpenAbout: () -> Unit,
    onDeleteAccount: () -> Unit,
    onSignOut: () -> Unit,
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
        AccountNavBar(title = "Аккаунт", onBack = onBack, testTag = ACCOUNT_BACK_TAG)

        VpnCard {
            Column {
                VpnRow(
                    key = "Аккаунт",
                    sub = "Привязан к Telegram",
                    value = "Telegram",
                    valueColor = VpnColors.TextSecondary,
                )
                VpnRow(
                    key = "Telegram ID",
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (state.telegramIdRevealed) state.telegramId else MASKED_TELEGRAM_ID,
                                color = VpnColors.Bone,
                                fontSize = 12.5.sp,
                                fontFamily = MonoFont,
                            )
                            LinkAction(
                                text = if (state.telegramIdRevealed) "Скрыть" else "Показать ›",
                                onClick = onToggleTelegramId,
                                testTag = ACCOUNT_REVEAL_ID_TAG,
                            )
                        }
                    },
                )
                VpnRow(
                    key = "Подключений активно",
                    sub = "${state.expiredConnections} истекли",
                    value = "${state.activeConnections} из ${state.totalConnections}",
                )
                VpnRow(
                    key = "Версия приложения",
                    // На Android сборок TestFlight нет: там сборка живёт 90 дней и
                    // гасится Apple. Здесь тот же смысл — срок жизни тестовой сборки,
                    // поэтому подпись говорит о тестировании, а не о чужом канале.
                    sub = "Сборка для тестирования",
                    value = appVersion,
                    valueMono = true,
                )
                VpnRow(
                    key = "Перестанет работать",
                    sub = "Обновите по ссылке из бота",
                    value = state.buildExpiryDate,
                    valueMono = true,
                )
                VpnRow(
                    key = "Подписка",
                    value = "до ${state.subscriptionUntil}",
                    valueMono = true,
                )
            }
        }

        SectionLabel(text = "Подключение")

        VpnCard {
            Column {
                VpnRow(
                    key = "Подтверждать смену страны",
                    sub = "Показывать предупреждение перед переключением",
                    trailing = {
                        VpnSwitch(
                            checked = state.confirmCountrySwitch,
                            onCheckedChange = onConfirmCountrySwitchChange,
                            testTag = ACCOUNT_CONFIRM_SWITCH_TAG,
                        )
                    },
                )
            }
        }

        UpdateSpacing()

        VpnCard {
            Column {
                VpnRow(
                    key = "Техподдержка",
                    sub = "Чат в Telegram",
                    trailing = { LinkAction("Написать ›", onSupportChat, ACCOUNT_SUPPORT_CHAT_TAG) },
                )
            }
        }

        // Кнопка не «копирует код», а собирает отчёт и открывает бота: подпись
        // говорит, что произойдёт, а не как это устроено внутри.
        VpnButton(
            text = "Отправить отчёт в поддержку",
            onClick = onSendDiagnostics,
            role = ButtonRole.Ghost,
            testTag = ACCOUNT_SEND_DIAGNOSTICS_TAG,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = "Отчёт без личных данных — вставится в чат поддержки.",
            color = VpnColors.Ash,
            fontSize = 11.5.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 7.dp, start = 2.dp),
        )

        VpnButton(
            text = "О сервисе",
            onClick = onOpenAbout,
            role = ButtonRole.Ghost,
            testTag = ACCOUNT_ABOUT_TAG,
            modifier = Modifier.padding(top = 12.dp),
        )

        // Разделитель перед необратимыми действиями: он отделяет их от всего
        // остального экрана, чтобы стирание аккаунта не стояло в одном ряду с
        // настройками.
        Box(
            modifier = Modifier
                .padding(top = 22.dp)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(VpnColors.Hairline),
        )

        SectionLabel(text = "Опасное действие")

        VpnButton(
            text = "Удалить аккаунт",
            onClick = onDeleteAccount,
            role = ButtonRole.Destructive,
            testTag = ACCOUNT_DELETE_TAG,
        )
        VpnButton(
            text = "Выйти из аккаунта",
            onClick = onSignOut,
            role = ButtonRole.Ghost,
            testTag = ACCOUNT_LOGOUT_TAG,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Отбивка между карточками одного потока. Вынесена, чтобы значения не разъезжались. */
@Composable
private fun UpdateSpacing() {
    Spacer(Modifier.height(8.dp))
}

/** Верхняя навигация: назад, центрированный заголовок, симметричный отступ справа. */
@Composable
internal fun AccountNavBar(title: String, onBack: () -> Unit, testTag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = MinTouchTarget)
                .testTag(testTag)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "‹ Назад", color = VpnColors.Ice, fontSize = 14.5.sp)
        }
        Text(
            text = title,
            color = VpnColors.Bone,
            fontSize = 16.5.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(48.dp))
    }
}

/** Небольшая текстовая ссылка. Зона ≥44pt: попасть в строку 13sp пальцем трудно. */
@Composable
internal fun LinkAction(text: String, onClick: () -> Unit, testTag: String? = null) {
    Box(
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = VpnColors.Ice, fontSize = 13.5.sp)
    }
}

/** Роль кнопки. Различие смысловое: красный — только у [Destructive]. */
private enum class ButtonRole { Primary, Ghost, Destructive }

/**
 * Кнопка во всю ширину.
 *
 * Деструктивная — прозрачная с красным контуром и красным текстом: заливка
 * красным притягивала бы взгляд сильнее, чем main-кнопка, хотя удаление не
 * должно быть самым заметным действием экрана.
 */
@Composable
private fun VpnButton(
    text: String,
    onClick: () -> Unit,
    role: ButtonRole,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    val container: Color
    val content: Color
    val outline: Color?
    when (role) {
        ButtonRole.Primary -> {
            container = VpnColors.Bone
            content = VpnColors.Void
            outline = null
        }

        ButtonRole.Ghost -> {
            container = Color.Transparent
            content = VpnColors.Bone
            outline = VpnColors.Edge
        }

        ButtonRole.Destructive -> {
            container = Color.Transparent
            content = VpnColors.Red
            outline = VpnColors.Red
        }
    }

    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(VpnRadii.Button.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.5f),
            disabledContentColor = content.copy(alpha = 0.5f),
        ),
        border = outline?.let { BorderStroke(0.5.dp, it.copy(alpha = 0.5f)) },
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        Text(text = text, fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
    }
}
