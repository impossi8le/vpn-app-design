package com.impossi8le.vpnapp.network

/**
 * Типизированная ошибка обращения к серверу.
 *
 * Отдельный тип вместо исключений: вызывающий обязан различать «сессия истекла»
 * (отправить на экран входа), «конфиг отозван» (показать состояние) и «сеть
 * недоступна» (предложить повторить). Свести их к одному `Exception` — значит
 * потерять реакцию UI, которую требует контракт §5.
 */
sealed interface ApiError {
    /** Тело ответа не разобралось, либо сервер вернул неожиданный статус. */
    data class Unexpected(val statusCode: Int, val body: String) : ApiError

    /** Сеть недоступна или истёк таймаут. Повтор осмыслен. */
    data class Network(val cause: String) : ApiError

    /** 401: сессия истекла. Клиент обязан очистить SessionStore и показать вход. */
    data object Unauthorized : ApiError

    /** 403 account_blocked. */
    data object AccountBlocked : ApiError

    /** 403 config_revoked / 410 config_retired — доступ к конфигу отозван. */
    data object ConfigRevoked : ApiError

    /** 402/403 subscription_expired. */
    data object SubscriptionExpired : ApiError

    /** 404 config_not_found или operation_not_found. */
    data object NotFound : ApiError

    /**
     * 401 invalid_secret на опросе входа: секрет не совпал с тем, чей хеш
     * отправляли при `/auth/link`. Операция испорчена, нужен новый вход.
     */
    data object InvalidSecret : ApiError

    /**
     * 403 nonce_mismatch: пользователь ввёл не тот код, что показал бот.
     * Отличается от [InvalidSecret] — здесь операция ещё жива, код можно ввести
     * заново, и пользователю нужно сказать именно это.
     */
    data object NonceMismatch : ApiError

    /** 429 rate_limited. Повтор осмыслен после паузы. */
    data object RateLimited : ApiError

    /** 409: код занят либо сессия уже использована. */
    data class Conflict(val code: String) : ApiError
}

/** Известный машиночитаемый код из тела ошибки (контракт §0). */
data class ApiErrorBody(val code: String, val message: String?, val retryable: Boolean?)
