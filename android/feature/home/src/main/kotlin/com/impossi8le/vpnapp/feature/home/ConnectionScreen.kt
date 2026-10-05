package com.impossi8le.vpnapp.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.BadgeTone
import com.impossi8le.vpnapp.core.ui.CountryMark
import com.impossi8le.vpnapp.core.ui.MinTouchTarget
import com.impossi8le.vpnapp.core.ui.SectionLabel
import com.impossi8le.vpnapp.core.ui.StatusHeadline
import com.impossi8le.vpnapp.core.ui.StatusRings
import com.impossi8le.vpnapp.core.ui.VpnBadge
import com.impossi8le.vpnapp.core.ui.VpnCard
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnGhostButton
import com.impossi8le.vpnapp.core.ui.VpnNavBar
import com.impossi8le.vpnapp.core.ui.VpnPrimaryButton
import com.impossi8le.vpnapp.core.ui.VpnRow
import com.impossi8le.vpnapp.core.ui.MonoFont
import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import androidx.compose.material3.Text

/**
 * Главный экран: состояние подключения.
 *
 * Один экран на восемь состояний из макета, а не восемь экранов. Причина: все
 * они различаются текстом, цветом кольца и действием кнопки, но не составом —
 * кольца, список подключений и нижняя кнопка есть всегда. Разводить их по
 * маршрутам значило бы синхронизировать маршрут с состоянием туннеля, а маршрут
 * можно выставить неверно, тогда как состояние приходит от сервиса.
 *
 * **Раскладка ничего не решает.** Что показать — приходит в [presentation], это
 * чистая функция, и её проверяют тесты. Здесь только показать. Дефект ложного
 * зелёного живёт в решении «какой цвет», а не в раскладке, и проверять его по
 * рендеру дороже и позже.
 */
@Composable
fun ConnectionScreen(
    status: ConnectionStatus,
    configs: List<ConfigRowState>,
    onAction: (StatusAction) -> Unit,
    onOpenAccount: () -> Unit,
    onRefreshConfigs: () -> Unit,
    onSelectConfig: (String) -> Unit,
    onSwitchCountry: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Список подключений сейчас перезагружается.
     *
     * Нужен, чтобы нажатие на «Обновить список» что-то показывало: раньше
     * запрос уходил молча, и человек не знал, услышали ли его. Это состояние
     * списка, а не туннеля, и на статус защиты оно влиять не может (§6) —
     * отсюда и живёт на подписи раздела, а не рядом с кольцами.
     */
    refreshing: Boolean = false,
    /** Предупреждение об окне без защиты: показывается при переключении страны. */
    switchingWarning: Boolean = false,
    /** Показывать ли системный запрос разрешения перед первым подключением. */
    startProgressText: String? = null,
    /**
     * Почему подключение не началось: нет активных подключений, подписка истекла,
     * доступ отозван, нет сети. `null` — причины нет, экран как обычно.
     *
     * Отдельно от [switchingWarning]: тот значит «трафик прямо сейчас без
     * защиты», здесь же туннель просто НЕ подняли — это разные вещи, и свести
     * их в один флаг значило бы соврать читателю.
     */
    notice: String? = null,
) {
    val presentation = status.presentation()

    Column(modifier = modifier.fillMaxSize().background(VpnColors.Void)) {
        // Шапка: заголовок и вход в аккаунт. Кнопка «назад» тут не нужна —
        // это корневой экран, и возврат с него закрывает приложение.
        Row(modifier = Modifier.padding(horizontal = 18.dp)) {
            VpnNavBar(
                title = "Подключение",
                action = "Аккаунт",
                onAction = onOpenAccount,
            )
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (switchingWarning) {
                item {
                    // ЕДИНСТВЕННОЕ место, где красный оправдан: соединение
                    // действительно рвётся, и трафик в эти секунды не защищён.
                    // См. макет, подпись «6. Смена страны».
                    com.impossi8le.vpnapp.core.ui.VpnNotice(
                        text = "Трафик временно не защищён. Идёт переключение между подключениями.",
                        tone = com.impossi8le.vpnapp.core.ui.Tone.Danger,
                        icon = "!",
                        testTag = CONNECTION_SWITCH_WARNING_TAG,
                    )
                }
            }

            // Причина, по которой подключение не началось: профиль не готов.
            // Тот же блок-предупреждение, что и у переключения страны, но НЕ
            // Danger: туннель просто не подняли, трафика без защиты нет — и
            // красный здесь читался бы как уже случившаяся утечка.
            if (notice != null) {
                item {
                    com.impossi8le.vpnapp.core.ui.VpnNotice(
                        text = notice,
                        tone = com.impossi8le.vpnapp.core.ui.Tone.Warning,
                        icon = "!",
                        testTag = CONNECTION_NOTICE_TAG,
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            top = if (switchingWarning || notice != null) 12.dp else 0.dp,
                            bottom = 4.dp,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Кольца неинтерактивны: в первой версии макета они выглядели
                    // нажимаемыми, и человек пытался «включить VPN» тапом по картинке.
                    StatusRings(
                        tone = presentation.ring,
                        modifier = Modifier.testTag(CONNECTION_RINGS_TAG),
                    )
                    Box(modifier = Modifier.padding(top = 13.dp)) {
                        StatusHeadline(
                            title = presentation.title,
                            detail = presentation.detail,
                            accent = presentation.accent,
                            titleTestTag = CONNECTION_TITLE_TAG,
                            detailTestTag = CONNECTION_DETAIL_TAG,
                        )
                    }
                }
            }

            // Блок «Защита»: отвечает на вопрос «а оно правда работает?».
            // Показывается только когда есть что показать: до подключения
            // проверять нечего, и блок с прочерками был бы шумом.
            if (presentation.protection.status != ProtectionBlockStatus.Unavailable) {
                item {
                    ProtectionCard(
                        block = presentation.protection,
                        onReverify = { onAction(StatusAction.Retry) },
                    )
                }
            }

            item {
                SectionLabel(
                    text = "Подключения",
                    action = if (refreshing) "Обновление…" else "Обновить список",
                    onAction = onRefreshConfigs,
                )
            }

            // Видимая реакция на «Обновить список». Без неё нажатие не давало
            // никакого сигнала, что запрос ушёл и выполняется.
            if (refreshing) {
                item {
                    Text(
                        "Идёт обновление — запрашиваем список подключений…",
                        color = VpnColors.Mist,
                        fontSize = 12.sp,
                        fontFamily = MonoFont,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .testTag(CONNECTION_REFRESHING_TAG),
                    )
                }
            }

            items(configs, key = { it.id }) { config ->
                ConfigRow(
                    config = config,
                    onClick = { onSelectConfig(config.id) },
                    onSwitch = { onSwitchCountry(config.id) },
                )
            }

            item { Box(modifier = Modifier.padding(bottom = 12.dp)) }
        }

        // Нижний бар: кнопка не сдвигается и не меняет размер между состояниями —
        // меняется только текст и заливка. Это из макета, и это заметно: кнопка,
        // прыгающая по экрану, читается как ошибка.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            // Прогресс подключения: живой счётчик вместо немого индикатора.
            if (startProgressText != null) {
                Text(
                    startProgressText,
                    color = VpnColors.Ash,
                    fontSize = 12.sp,
                    fontFamily = MonoFont,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .testTag(CONNECTION_PROGRESS_TAG),
                )
            }

            when (presentation.action) {
                // Основное действие — белая кнопка: цвет на экране принадлежит
                // состоянию, а не бренду, и белая не спорит с индикатором.
                StatusAction.Connect,
                StatusAction.Retry,
                StatusAction.RefreshAccess,
                StatusAction.OpenSettings,
                -> VpnPrimaryButton(
                    text = presentation.actionLabel,
                    onClick = { onAction(presentation.action) },
                    testTag = CONNECTION_ACTION_TAG,
                )

                StatusAction.Cancel,
                StatusAction.Disconnect,
                -> VpnGhostButton(
                    text = presentation.actionLabel,
                    onClick = { onAction(presentation.action) },
                    testTag = CONNECTION_ACTION_TAG,
                )
            }
        }
    }
}

/**
 * Карточка «Защита».
 *
 * Показывает ИТОГ замера и его факты. Итог — не украшение: «проверено» и
 * «не проверено» различаются, и различие видно без цвета.
 */
@Composable
private fun ProtectionCard(block: ProtectionBlock, onReverify: () -> Unit) {
    VpnCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "СОЕДИНЕНИЕ",
                    color = VpnColors.Ash,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    block.status.label,
                    color = when (block.status) {
                        ProtectionBlockStatus.Confirmed -> VpnColors.Green
                        ProtectionBlockStatus.Failed -> VpnColors.Amber
                        ProtectionBlockStatus.Checking -> VpnColors.Mist
                        else -> VpnColors.Ash
                    },
                    fontSize = 12.5.sp,
                    modifier = Modifier.testTag(CONNECTION_PROTECTION_STATUS_TAG),
                )
            }

            if (block.server != null) {
                VpnRow(key = "Сервер", value = block.server, valueMono = true)
            }
            if (block.ipv6 != null) {
                VpnRow(key = "IPv6", value = block.ipv6)
            }
            if (block.dns != null) {
                VpnRow(key = "DNS", value = block.dns)
            }
            if (block.killSwitch != null) {
                VpnRow(key = "Kill switch", value = block.killSwitch)
            }

            // Перепроверка предлагается только там, где она имеет смысл: замер
            // провалился. В остальных состояниях это кнопка «ничего не делает».
            if (block.status == ProtectionBlockStatus.Failed) {
                VpnGhostButton(
                    text = "Проверить снова",
                    onClick = onReverify,
                    height = 46.dp,
                    modifier = Modifier.padding(top = 10.dp),
                    testTag = CONNECTION_REVERIFY_TAG,
                )
            }
        }
    }
}

/**
 * Строка подключения.
 *
 * Истёкшее подключение НЕ может выглядеть активным: в первой версии макета
 * экран одновременно писал «подключение» и «истёк» — это исправлено здесь тем,
 * что состояние строки приходит готовым в [ConfigRowState].
 */
@Composable
private fun ConfigRow(
    config: ConfigRowState,
    onClick: () -> Unit,
    onSwitch: () -> Unit,
) {
    val dead = config.status == ConfigRowStatus.Expired
    val selected = config.status == ConfigRowStatus.Selected
            || config.status == ConfigRowStatus.Active

    VpnCard(
        background = if (dead) VpnColors.Void else VpnColors.Carbon,
        border = if (selected) VpnColors.Ice.copy(alpha = 0.5f) else VpnColors.Hairline,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable { if (dead) Unit else if (selected) onSwitch() else onClick() }
            .testTag(configRowTag(config.id)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            CountryMark(code = config.countryCode, selected = selected, dead = dead)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    config.name,
                    color = if (dead) VpnColors.TextSecondary else VpnColors.Bone,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    config.subtitle,
                    color = VpnColors.Ash,
                    fontSize = 12.sp,
                )
            }
            when (config.status) {
                ConfigRowStatus.Selected ->
                    VpnBadge("Выбрано", BadgeTone.Selected)

                ConfigRowStatus.Active ->
                    VpnBadge("Подключено", BadgeTone.Active)

                ConfigRowStatus.Expired ->
                    VpnBadge("Истёк", BadgeTone.Dead)

                ConfigRowStatus.Available -> Unit
            }
        }
    }
}

/** Строка списка подключений, как её показывает экран. */
data class ConfigRowState(
    val id: String,
    val name: String,
    val countryCode: String,
    val subtitle: String,
    val status: ConfigRowStatus,
)

/**
 * Состояние строки.
 *
 * [Expired] отдельным значением, а не флагом: истёкшее подключение нельзя
 * выбрать, и это должно быть видно типом, а не проверкой флага в трёх местах.
 */
enum class ConfigRowStatus { Selected, Active, Available, Expired }

/** Теги для тестов: те же строки используются в UI и в инструментальных тестах. */
const val CONNECTION_ACTION_TAG = "connection_action"
const val CONNECTION_TITLE_TAG = "connection_title"
const val CONNECTION_DETAIL_TAG = "connection_detail"
const val CONNECTION_RINGS_TAG = "connection_rings"
const val CONNECTION_PROTECTION_STATUS_TAG = "connection_protection_status"
const val CONNECTION_REVERIFY_TAG = "connection_reverify"
const val CONNECTION_PROGRESS_TAG = "connection_progress"
const val CONNECTION_SWITCH_WARNING_TAG = "connection_switch_warning"
const val CONNECTION_NOTICE_TAG = "connection_notice"
const val CONNECTION_REFRESHING_TAG = "connection_refreshing"

/** Тег строки подключения: один на всех, различается по id. */
fun configRowTag(id: String): String = "config_row_$id"