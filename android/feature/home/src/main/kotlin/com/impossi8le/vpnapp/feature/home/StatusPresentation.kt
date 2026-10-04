package com.impossi8le.vpnapp.feature.home

import androidx.compose.ui.graphics.Color
import com.impossi8le.vpnapp.core.ui.RingTone
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.domain.model.ConnectionStatus

/**
 * Что показать на главном экране для текущего состояния.
 *
 * Отдельный тип, а не разметка внутри Composable: это решение «какой текст,
 * какой цвет и что делает кнопка», и именно здесь живёт дефект ложного зелёного,
 * который находили на UX-ревью. Как чистая функция оно проверяется тестом, а не
 * глазами по рендеру.
 */
data class StatusPresentation(
    val title: String,
    val detail: String,
    val accent: Color,
    val ring: RingTone,
    /** Текст главной кнопки. Матрица кнопки — из `docs/design/mockup.html`. */
    val actionLabel: String,
    /** Что делает главная кнопка. */
    val action: StatusAction,
    /**
     * Блок «Защита»: что именно подтверждено замером.
     *
     * **Зачем он отдельно от подзаголовка.** Макет вынес честность сюда: в
     * состоянии «туннель поднят» подзаголовок говорит «Проверяем, что трафик
     * идёт через него…», а блок рядом — «не проверено». Так пользователь видит и
     * процесс, и то, что защиты пока нет, не смешивая их в одной строке.
     *
     * Пустой список означает «показывать нечего»: до подключения проверять
     * нечего, и пустой блок честнее блока с прочерками.
     */
    val protection: ProtectionBlock,
)

/**
 * Что показать в блоке защиты.
 *
 * [status] — это НЕ «защищено»: строка описывает состояние замера, и только
 * [ProtectionBlockStatus.Confirmed] означает состоявшийся положительный замер.
 * Остальные значения прямо говорят, что защиты нет или она не подтверждена.
 */
data class ProtectionBlock(
    val status: ProtectionBlockStatus,
    val server: String? = null,
    val ipv6: String? = null,
    val dns: String? = null,
    val killSwitch: String? = null,
    /** Когда замер состоялся, если состоялся. */
    val checkedAt: String? = null,
)

/** Итог блока защиты. Разные значения — разный текст, и это существенно. */
enum class ProtectionBlockStatus(val label: String) {
    /** Замер не проводился: проверять нечего. */
    NotChecked("не проверено"),

    /** Замер идёт прямо сейчас. */
    Checking("проверяем…"),

    /** Замер прошёл и подтвердил защиту. */
    Confirmed("проверено"),

    /** Замер прошёл и НЕ подтвердил. */
    Failed("не пройдено"),

    /** Замера нет и быть не может: туннель не поднят. */
    Unavailable("нет туннеля"),
}

/**
 * Действие главной кнопки.
 *
 * Типом, а не лямбдой: экран должен решать, что вызвать, а тест — проверять, что
 * в этом состоянии предложено именно это действие. Лямбда в презентации
 * протащила бы зависимость на ViewModel в чистую функцию.
 */
enum class StatusAction {
    /** Поднять туннель. */
    Connect,

    /** Прервать попытку. Отличается от [Disconnect]: отключать ещё нечего. */
    Cancel,

    /** Опустить туннель. */
    Disconnect,

    /** Повторить попытку после сбоя. */
    Retry,

    /** Открыть системные настройки: без разрешения подключиться нельзя. */
    OpenSettings,

    /** Обновить данные подписки. НЕ ведёт к покупке — это анти-стеринг 3.1.1. */
    RefreshAccess,
}

/**
 * Сопоставление состояния и подачи.
 *
 * Ключевое правило, ради которого эта функция существует отдельно:
 * **зелёный цвет выдаётся только для [ConnectionStatus.Protected]**, то есть
 * только по результату замера. Состояние «туннель поднят»
 * ([ConnectionStatus.VerifyingProtection]) получает нейтральные кольца и текст
 * «не проверено» — потому что поднятый интерфейс не доказывает, что трафик идёт
 * через него.
 *
 * Матрица кнопки взята из макета дословно, включая неочевидные случаи:
 *  - в состоянии «подключение» кнопка «Отменить», а не «Отключить»: раньше
 *    предлагалось отключить то, чего ещё нет;
 *  - в состоянии «нет разрешения» кнопка ведёт в настройки, потому что обойти
 *    системный диалог нельзя;
 *  - «обновить доступ» не ведёт к покупке.
 */
fun ConnectionStatus.presentation(): StatusPresentation = when (this) {
    ConnectionStatus.Disconnected -> StatusPresentation(
        title = "Не подключено",
        detail = "Выберите подключение и нажмите «Подключить»",
        // Нейтральный, не красный: выключенное состояние — это не ошибка.
        accent = VpnColors.TextSecondary,
        ring = RingTone.Idle,
        actionLabel = "Подключить",
        action = StatusAction.Connect,
        // Проверять нечего: туннеля нет.
        protection = ProtectionBlock(ProtectionBlockStatus.Unavailable),
    )

    ConnectionStatus.Connecting -> StatusPresentation(
        title = "Подключение",
        detail = "Устанавливаем соединение",
        // Янтарный — только идущий процесс.
        accent = VpnColors.Amber,
        ring = RingTone.Process,
        actionLabel = "Отменить",
        action = StatusAction.Cancel,
        protection = ProtectionBlock(ProtectionBlockStatus.Unavailable),
    )

    ConnectionStatus.VerifyingProtection -> StatusPresentation(
        title = "Туннель поднят",
        // Честно: система уже рисует значок VPN, а мы ещё ничего не подтвердили.
        detail = "Защита не проверена. Проверяем, что трафик идёт через туннель…",
        accent = VpnColors.Mist,
        // Нейтральные кольца: процесс уже окончен, результата ещё нет. См. RingTone.Verifying.
        ring = RingTone.Verifying,
        actionLabel = "Отключить",
        action = StatusAction.Disconnect,
        protection = ProtectionBlock(ProtectionBlockStatus.Checking),
    )

    is ConnectionStatus.Protected -> StatusPresentation(
        // Единственное место, где уместен зелёный, и единственный источник —
        // результат замера.
        title = "Подключено и защищено",
        detail = "Трафик идёт через туннель",
        accent = VpnColors.Green,
        ring = RingTone.Protected,
        actionLabel = "Отключить",
        action = StatusAction.Disconnect,
        // Единственное место, где факты замера можно показать как подтверждённые.
        protection = ProtectionBlock(
            status = ProtectionBlockStatus.Confirmed,
            ipv6 = if (evidence.ipv6Closed) "закрыт" else null,
            dns = if (evidence.dnsInside) "через туннель" else null,
        ),
    )

    is ConnectionStatus.ProtectionFailed -> StatusPresentation(
        // Янтарный, а не красный: соединение есть, но замер не подтвердил
        // защиту. Красный здесь кричал бы о разрыве, которого нет.
        title = "Трафик не идёт через туннель",
        detail = when (verdict.failure) {
            com.impossi8le.vpnapp.domain.protection.ProtectionFailure.ProbeUnavailable ->
                "Соединение установлено, но проверка не прошла. Не открывайте важные сайты."

            com.impossi8le.vpnapp.domain.protection.ProtectionFailure.Inconclusive ->
                "Часть трафика может идти мимо туннеля. Не открывайте важные сайты."
        },
        accent = VpnColors.Amber,
        // Внимание, но не опасность: соединение есть, защиты нет.
        ring = RingTone.Attention,
        actionLabel = "Отключить",
        action = StatusAction.Disconnect,
        protection = ProtectionBlock(
            status = ProtectionBlockStatus.Failed,
            dns = verdict.failure.let {
                if (it == com.impossi8le.vpnapp.domain.protection.ProtectionFailure.Inconclusive)
                    "возможна утечка" else null
            },
        ),
    )

    is ConnectionStatus.Failed -> StatusPresentation(
        title = "Не удалось подключиться",
        detail = reason,
        // Красный: подключение не состоялось, и это видно пользователю.
        accent = VpnColors.Red,
        ring = RingTone.Danger,
        actionLabel = "Повторить",
        action = StatusAction.Retry,
        // Туннель не поднялся: проверять защиту нечего, и показывать прочерки
        // означало бы предлагать проверить то, чего нет.
        protection = ProtectionBlock(ProtectionBlockStatus.Unavailable),
    )
}