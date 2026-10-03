package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.config.ConfigFetchError

/**
 * Перевод сетевой ошибки в доменную для получения конфига.
 *
 * Мост нужен, потому что `core:config` не имеет права зависеть от `core:network`
 * (§1): домен объявляет тип, сеть его заполняет, а хранилище читает. Без этого
 * моста различие между истёкшей подпиской, отозванным конфигом и недоступной
 * сетью теряется по дороге, и экран показывает неверную причину.
 *
 * Контракт §4 перечисляет коды отдельно для `/config/{id}`, и здесь они
 * переводятся один в один.
 */
fun ApiError.toConfigFetchError(): ConfigFetchError = when (this) {
    ApiError.SubscriptionExpired -> ConfigFetchError.SubscriptionExpired
    ApiError.ConfigRevoked -> ConfigFetchError.ConfigRevoked
    ApiError.NotFound -> ConfigFetchError.NotFound
    ApiError.Unauthorized -> ConfigFetchError.Unauthorized
    ApiError.RateLimited -> ConfigFetchError.RateLimited
    is ApiError.Network -> ConfigFetchError.NetworkUnavailable
    is ApiError.Unexpected -> ConfigFetchError.Unexpected(statusCode)
    // Ниже — ошибки входа; на пути получения конфига они означают неожиданный
    // ответ сервера, а не отдельное состояние экрана.
    ApiError.AccountBlocked -> ConfigFetchError.Unexpected(statusCode = 403)
    ApiError.InvalidSecret -> ConfigFetchError.Unexpected(statusCode = 401)
    ApiError.NonceMismatch -> ConfigFetchError.Unexpected(statusCode = 403)
    is ApiError.Conflict -> ConfigFetchError.Unexpected(statusCode = 409)
}
