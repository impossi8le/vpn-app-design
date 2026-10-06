package com.impossi8le.vpnapp.domain.update

/**
 * Основной источник, а при его отказе — запасной.
 *
 * Переход на свой хостинг не должен ронять раздачу в день выката: пока серверные
 * эндпоинты не отвечают, обновления приходят с прежнего места.
 *
 * Оговорка про порог: [UpdateService.minSupported] отдаёт `null` и когда порога
 * нет, и когда узнать не удалось, — различить эти случаи интерфейс не даёт.
 * Поэтому при `null` от основного мы предпочитаем его значение и спрашиваем
 * запасной: если у основного порога честно нет, а у запасного он окажется выше,
 * поддерживаемая сборка может быть ошибочно заблокирована. Мы всё же идём на
 * этот компромисс, потому что запасной — прежнее место раздачи и его порог
 * относится к тому же продукту.
 */
class FallbackUpdateService(
    private val primary: UpdateService,
    private val fallback: UpdateService,
) : UpdateService {

    override suspend fun latestRelease(): Result<ReleaseInfo> {
        val primaryResult = primary.latestRelease()
        if (primaryResult.isSuccess) return primaryResult
        return try {
            val fallbackResult = fallback.latestRelease()
            if (fallbackResult.isSuccess) fallbackResult else primaryResult
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Оба источника отказали — отдаём отказ ОСНОВНОГО: он описывает
            // нашу цель, а не прежнее место раздачи.
            primaryResult
        }
    }

    override suspend fun minSupported(): Int? =
        primary.minSupported() ?: fallback.minSupported()
}
