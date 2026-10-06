package com.impossi8le.vpnapp.domain.update

/**
 * Основной источник, а при его отказе — запасной.
 *
 * Переход на свой хостинг не должен ронять раздачу в день выката: пока серверные
 * эндпоинты не отвечают, обновления приходят с прежнего места.
 *
 * Оговорка про порог: [UpdateService.minSupported] отдаёт `null` и когда порога
 * нет, и когда узнать не удалось. Поэтому при `null` от основного спрашиваем
 * запасной — даже если порога у основного честно нет. Разница безвредна: пороги
 * у обоих источников обязаны совпадать, а лишний порог безвреднее пропущенного.
 */
class FallbackUpdateService(
    private val primary: UpdateService,
    private val fallback: UpdateService,
) : UpdateService {

    override suspend fun latestRelease(): Result<ReleaseInfo> =
        primary.latestRelease().recoverCatching { fallback.latestRelease().getOrThrow() }

    override suspend fun minSupported(): Int? =
        primary.minSupported() ?: fallback.minSupported()
}
