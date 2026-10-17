package com.impossi8le.vpnapp.domain.update

/**
 * Основной источник, а при его отказе — запасной.
 *
 * Переход на свой хостинг не должен ронять раздачу в день выката: пока серверные
 * эндпоинты не отвечают, обновления приходят с прежнего места.
 *
 * **Успех основного не значит актуальность.** Сервер может отвечать старейшей
 * сборкой, и тогда запасной молчал бы, а исправления из свежих релизов не
 * доезжали бы до пользователя (issue #33: 134 на сервере против 136 на GitHub).
 * Поэтому при ответе обоих берётся более новая версия — вместе со ссылкой того
 * источника, чью версию выбрали.
 *
 * Оговорка про порог: [UpdateService.minSupported] отдаёт `null` и когда порога
 * нет, и когда узнать не удалось, — различить эти случаи интерфейс не даёт.
 * Поэтому при `null` от основного мы спрашиваем запасной. Обратная сторона: если
 * у основного порога честно нет, а у запасного он выше, поддерживаемая сборка
 * может быть ошибочно заблокирована. Компромисс принят, потому что запасной —
 * прежнее место раздачи того же продукта, и его порог относится к той же линейке.
 */
class FallbackUpdateService(
    private val primary: UpdateService,
    private val fallback: UpdateService,
) : UpdateService {

    override suspend fun latestRelease(): Result<ReleaseInfo> {
        val primaryResult = primary.latestRelease()

        // **Успех основного — ещё не актуальность.** Сервер может быть жив и
        // отдавать сборку старее настоящей: живьём было 134 при 136 на GitHub,
        // и исправления из свежих релизов до пользователя не доезжали (#33).
        // Поэтому запасной спрашиваем и при успехе основного тоже.
        val fallbackResult = try {
            fallback.latestRelease()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Запасной бросил — считаем, что его нет.
            Result.failure(IllegalStateException("запасной источник недоступен"))
        }

        val primaryInfo = primaryResult.getOrNull()
            // Основной отказал: решает запасной, если ответил, иначе отказ
            // ОСНОВНОГО — он описывает нашу цель, а не прежнее место раздачи.
            ?: return if (fallbackResult.isSuccess) fallbackResult else primaryResult

        val fallbackInfo = fallbackResult.getOrNull() ?: return primaryResult
        return Result.success(newerOf(primaryInfo, fallbackInfo))
    }

    /**
     * Более новая из двух версий вместе со СВОЕЙ ссылкой.
     *
     * Ссылку берём у того же источника, чью версию выбрали: иначе покажем
     * «доступна 136», а скачаем 134 — и предложение обновиться приведёт к уже
     * установленному.
     *
     * При равенстве версий и при неразобранном теге основного остаётся основной:
     * своя раздача предпочтительнее (докачка, контроль, лимиты GitHub), а
     * сравнивать не с чем.
     */
    private fun newerOf(primary: ReleaseInfo, fallback: ReleaseInfo): ReleaseInfo {
        val primaryCode = parseReleaseTag(primary.tagName)
            // Тег основного не нашего формата — сравнивать не с чем. Тогда
            // годится запасной: у него формат верный, а сборка заведомо
            // существует. Отдавать неизвестный тег значило бы предложить
            // обновление, о котором мы ничего не знаем.
            ?: return fallback.takeIf { parseReleaseTag(it.tagName) != null } ?: primary

        val fallbackCode = parseReleaseTag(fallback.tagName) ?: return primary
        return if (fallbackCode > primaryCode) fallback else primary
    }

    override suspend fun minSupported(): Int? =
        primary.minSupported() ?: fallback.minSupported()
}
