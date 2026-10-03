package com.impossi8le.vpnapp.domain.config

/**
 * Почему не удалось получить конфиг.
 *
 * Отдельный тип в домене, а не исключение из `core:network`: `core:config` не
 * имеет права зависеть от сетевого модуля (направление зависимостей — §1),
 * а различать эти случаи обязан. Контракт §5 предписывает разную реакцию для
 * каждого: истёкшая подписка — состояние экрана без обращения к серверу,
 * отозванный конфиг — предложение выбрать другой, недоступная сеть — повторить.
 *
 * Свести их к «не получилось» значит потерять реакцию UI и показать пользователю
 * неверную причину.
 */
sealed interface ConfigFetchError {
    /** Подписка истекла: конфиг не выдаётся, трогать установленный нельзя. */
    data object SubscriptionExpired : ConfigFetchError

    /** Доступ к конфигу отозван на сервере: нужен другой. */
    data object ConfigRevoked : ConfigFetchError

    /** Конфиг не найден: список на экране устарел. */
    data object NotFound : ConfigFetchError

    /** Сеть недоступна или таймаут: повтор осмыслен. */
    data object NetworkUnavailable : ConfigFetchError

    /** Сессия истекла: нужен повторный вход. */
    data object Unauthorized : ConfigFetchError

    /** Слишком часто: повтор осмыслен после паузы. */
    data object RateLimited : ConfigFetchError

    /** Прочее: тело не разобралось или сервер ответил неожиданно. */
    data class Unexpected(val statusCode: Int) : ConfigFetchError
}

/**
 * Обёртка, чтобы протащить типизированную ошибку через `Result`.
 *
 * `Result` умеет нести только `Throwable`, а нам нужен тип, по которому
 * вызывающий примет решение.
 */
class ConfigFetchException(val error: ConfigFetchError) :
    Exception("не удалось получить конфиг: $error")
