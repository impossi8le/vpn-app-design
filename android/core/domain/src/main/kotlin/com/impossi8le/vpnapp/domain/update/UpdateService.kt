package com.impossi8le.vpnapp.domain.update

/**
 * Данные последнего релиза, как их отдал GitHub.
 *
 * Номер сборки здесь НЕ извлекается: разбор тега — правило домена
 * ([parseReleaseTag]), и оно должно тестироваться без сети.
 */
data class ReleaseInfo(val tagName: String, val apkUrl: String)

/** Почему не удалось узнать последнюю версию. */
sealed interface UpdateError {
    /** Релизов ещё нет — это не сбой, просто обновляться не с чего. */
    data object NotFound : UpdateError

    /** Нет связи или таймаут: повтор осмыслен. */
    data object NetworkUnavailable : UpdateError

    /** Исчерпан лимит анонимных запросов к GitHub API. */
    data object RateLimited : UpdateError

    /** Прочее: ответ не разобрался или в нём нет APK. */
    data class Unexpected(val statusCode: Int) : UpdateError
}

class UpdateException(val error: UpdateError) :
    Exception("не удалось узнать последнюю версию: $error")

/** Источник сведений о последней версии. */
interface UpdateService {
    suspend fun latestRelease(): Result<ReleaseInfo>

    /**
     * Наименьшая поддерживаемая версия. `null` — узнать не удалось (нет файла,
     * нет связи, мусор в файле). Возвращает не `Result`, а `null`, потому что
     * вызывающему нечего различать: любая неудача означает одно — порога нет,
     * блокировать нельзя.
     */
    suspend fun minSupported(): Int?
}
