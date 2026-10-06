package com.impossi8le.vpnapp.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.impossi8le.vpnapp.core.ui.MonoFont
import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import androidx.compose.material3.LinearProgressIndicator
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
    /**
     * Открыть экран «Обходы».
     *
     * Вход отсюда, с главного экрана, а не из «Аккаунта»: обходы — про то, куда
     * идёт трафик, то есть про само подключение, и рядом с ним их будут искать.
     */
    onOpenBypass: () -> Unit = {},
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
    /**
     * Подпись о том, что список подключений показан из кэша, а не из свежего
     * ответа. `null` — говорить нечего.
     *
     * Считается снаружи чистой `stalenessNote`. Отдельно от [refreshing]:
     * «идёт обновление» (процесс) и «данные сохранённые» (качество данных) —
     * разные факты, и §6 требует показывать второй явно, а не выдавать кэш за
     * текущее состояние.
     */
    configsStaleNote: String? = null,
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
    /**
     * Номер доступной версии или `null`. Баннер обновления — приглашение, а не
     * предупреждение: он не про защиту и не должен пугать. Поэтому нейтральный
     * тон, а не жёлтый/красный (ср. [switchingWarning] и [notice]).
     */
    updateVersionCode: Int? = null,
    /**
     * Процент скачанного обновления или `null`, если загрузка не идёт.
     *
     * Живёт на главном экране, а не только в «Аккаунте»: баннер обновления
     * стоит именно здесь, и без строки прогресса нажатие «Обновить» выглядит
     * как молчание — при 106 МБ это минуты на медленной сети.
     */
    updateProgress: Int? = null,
    onDownloadUpdate: () -> Unit = {},
    onDismissUpdateBanner: () -> Unit = {},
    /**
     * Имя работающего подключения, если известно. Показывается подзаголовком,
     * когда туннель поднят: пользователь просил видеть, какой конфиг работает.
     * `null` — имени нет, тогда подзаголовок нейтрален.
     */
    runningConfigName: String? = null,
    /**
     * Число активных обходов. Больше нуля — часть трафика идёт мимо туннеля, и
     * подзаголовок обязан об этом сказать: иначе «Подключено» читается как
     * «защищено всё» (см. §6 и [presentation]).
     */
    bypassCount: Int = 0,
    /**
     * Применяет ли это устройство исключения обходов (API 33+). `false` — обход
     * записан, но не действует; экран обязан сказать это, а не притворяться,
     * что часть трафика идёт напрямую.
     *
     * Дефолт `false` — безопасная сторона: умолчание не должно обещать
     * работающий обход, которого может не быть. Настоящее значение передаёт
     * [AppRoot] из `MainActivity`.
     */
    bypassSupported: Boolean = false,
    /**
     * Задуманы ли обходы в настройке пользователя вовсе — независимо от того,
     * сколько применилось и умеет ли это устройство. Экран обязан отличать
     * «обходов нет» от «обход задуман, но не применён», поэтому намерение идёт
     * отдельным входом, а не выводится из [bypassCount]: на API < 33 применено
     * всегда 0, и выведенная из счётчика недоступность стала бы нераспознаваемой.
     *
     * Дефолт `false` — безопасная сторона. Настоящее значение передаёт [AppRoot]
     * из `MainActivity`.
     */
    bypassConfigured: Boolean = false,
) {
    val presentation = status.presentation(
        runningConfigName = runningConfigName,
        bypassCount = bypassCount,
        bypassSupported = bypassSupported,
        bypassConfigured = bypassConfigured,
    )

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

            if (updateVersionCode != null) {
                item {
                    UpdateBanner(
                        versionCode = updateVersionCode,
                        progress = updateProgress,
                        onDownload = onDownloadUpdate,
                        onDismiss = onDismissUpdateBanner,
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

            item {
                // Вход в «Обходы» — отдельной строкой-карточкой над списком
                // подключений: видно и не спрятано в «Аккаунт». Счётчик обходов
                // показываем только когда они реально применены: на API < 33
                // `appliedBypass` всегда 0, и «0 обходов» здесь читалось бы как
                // «ничего не настроено», хотя список мог быть записан.
                VpnCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenBypass)
                            .heightIn(min = MinTouchTarget)
                            .testTag(CONNECTION_BYPASS_TAG),
                    ) {
                        Text(
                            "Обходы",
                            color = VpnColors.Bone,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (bypassCount > 0) {
                                "Активно обходов: $bypassCount"
                            } else {
                                "Сервисы и адреса, которые идут мимо туннеля"
                            },
                            color = VpnColors.Ash,
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
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

            // Честность о кэше (§6): пока список показан из сохранённого, экран
            // прямо говорит это — сохранённые данные не выдаём за текущие.
            // Раскладка рядом со списком, а не у колец: это свойство СПИСКА, а
            // не состояния защиты, и на кольца влиять не может.
            if (configsStaleNote != null) {
                item {
                    Text(
                        configsStaleNote,
                        color = VpnColors.Ash,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                            .testTag(CONNECTION_STALE_TAG),
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
const val CONNECTION_PROGRESS_TAG = "connection_progress"
const val CONNECTION_SWITCH_WARNING_TAG = "connection_switch_warning"
const val CONNECTION_NOTICE_TAG = "connection_notice"
const val CONNECTION_REFRESHING_TAG = "connection_refreshing"
const val CONNECTION_STALE_TAG = "connection_stale"
const val HOME_UPDATE_TAG = "home_update"
const val HOME_UPDATE_DISMISS_TAG = "home_update_dismiss"
const val HOME_UPDATE_PROGRESS_TAG = "home_update_progress"
const val CONNECTION_BYPASS_TAG = "connection_bypass"

/**
 * Плашка «доступна новая версия».
 *
 * Не [VpnNotice]: уведомление несёт тон и значок состояния (опасность,
 * предупреждение), а обновление — не состояние подключения. Здесь две ссылки —
 * обновиться или отложить, и обе видны сразу: спрятать «Позже» за крестик
 * значило бы заставить пользователя угадывать, как убрать плашку с глаз.
 *
 * Пока [progress] не `null`, идёт загрузка: вместо ссылок рисуется строка
 * прогресса. Ссылку «Обновить» в это время не показываем вовсе — второй тап по
 * ней запускал вторую загрузку (на телефоне сервер видел три параллельных
 * запроса одного APK), а тут кнопки просто нет.
 */
@Composable
private fun UpdateBanner(
    versionCode: Int,
    progress: Int?,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    VpnCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "Доступна новая версия 1.0.$versionCode",
                color = VpnColors.Bone,
                fontSize = 15.sp,
            )
            if (progress != null) {
                Text(
                    text = "Скачиваем обновление… $progress%",
                    color = VpnColors.Ice,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .testTag(HOME_UPDATE_PROGRESS_TAG),
                )
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            } else {
                Row(modifier = Modifier.padding(top = 6.dp)) {
                    Text(
                        text = "Обновить",
                        color = VpnColors.Ice,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clickable(onClick = onDownload)
                            .padding(vertical = 6.dp, horizontal = 4.dp)
                            .testTag(HOME_UPDATE_TAG),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Позже",
                        color = VpnColors.Ash,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .clickable(onClick = onDismiss)
                            .padding(vertical = 6.dp, horizontal = 4.dp)
                            .testTag(HOME_UPDATE_DISMISS_TAG),
                    )
                }
            }
        }
    }
}

/** Тег строки подключения: один на всех, различается по id. */
fun configRowTag(id: String): String = "config_row_$id"