package com.impossi8le.vpnapp.feature.home

import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.domain.model.ConnectionStatus

/**
 * Что показать на главном экране для текущего состояния.
 *
 * Отдельный тип, а не разметка внутри Composable: это решение «какой текст и
 * какой цвет», и именно здесь живёт дефект ложного зелёного, который находили
 * на UX-ревью. Как чистая функция оно проверяется тестом, а не глазами.
 */
data class StatusPresentation(
    val title: String,
    val detail: String,
    val accent: androidx.compose.ui.graphics.Color,
)

/**
 * Сопоставление состояния и подачи.
 *
 * Ключевое правило, ради которого эта функция существует отдельно:
 * **зелёный цвет выдаётся только для [ConnectionStatus.Protected]**, то есть
 * только по результату замера. Состояние «туннель поднят» ([VerifyingProtection])
 * получает нейтральный цвет и текст «не проверено» — потому что поднятый
 * интерфейс не доказывает, что трафик идёт через него.
 */
fun ConnectionStatus.presentation(): StatusPresentation = when (this) {
    ConnectionStatus.Disconnected -> StatusPresentation(
        title = "Отключено",
        detail = "Трафик идёт напрямую",
        accent = VpnColors.Mist,
    )

    ConnectionStatus.Connecting -> StatusPresentation(
        title = "Подключение…",
        detail = "Устанавливаем соединение",
        // Янтарный — только идущий процесс.
        accent = VpnColors.Amber,
    )

    ConnectionStatus.VerifyingProtection -> StatusPresentation(
        // Нейтральный цвет и честный текст: система уже рисует значок VPN,
        // а мы ещё ничего не подтвердили.
        title = "Туннель поднят",
        detail = "Защита не проверена",
        accent = VpnColors.Mist,
    )

    is ConnectionStatus.Protected -> StatusPresentation(
        // Единственное место, где уместен зелёный.
        title = "Защищено",
        detail = "Трафик идёт через туннель",
        accent = VpnColors.Green,
    )

    is ConnectionStatus.ProtectionFailed -> StatusPresentation(
        // Красный — реальная опасность: пользователь считает, что защищён, а замер не подтвердил.
        title = "Защита не подтверждена",
        detail = when (verdict.failure) {
            com.impossi8le.vpnapp.domain.protection.ProtectionFailure.ProbeUnavailable ->
                "Не удалось проверить: нет сети"
            com.impossi8le.vpnapp.domain.protection.ProtectionFailure.Inconclusive ->
                "Часть трафика может идти мимо туннеля"
        },
        accent = VpnColors.Red,
    )

    is ConnectionStatus.Failed -> StatusPresentation(
        title = "Не удалось подключиться",
        detail = reason,
        accent = VpnColors.Red,
    )
}
