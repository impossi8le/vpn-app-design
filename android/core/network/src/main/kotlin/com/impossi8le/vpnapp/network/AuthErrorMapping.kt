package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.auth.AuthPollError

/**
 * Перевод сетевой ошибки в доменную для опроса входа.
 *
 * Мост нужен по той же причине, что и `toConfigFetchError`: `feature:auth` не
 * имеет права зависеть от `core:network` (§1). Домен объявляет тип, сеть его
 * заполняет, ViewModel читает.
 *
 * Главное, что здесь различается, — `nonce_mismatch`. Это НЕ сбой связи:
 * операция жива, и пользователь должен вернуться к вводу кода, а не увидеть
 * «нет связи с сервером». Раньше это различие терялось в `.getOrElse`, и любой
 * провал опроса — включая неверный код — показывался как сетевой.
 *
 * Остальные ошибки опроса на этом пути не имеют отдельного состояния экрана:
 * вход либо продолжается (pending), либо завершается терминальным исходом
 * (`PollOutcome`); всё прочее — повод показать общую ошибку и предложить повтор.
 */
fun ApiError.toAuthPollError(): AuthPollError = when (this) {
    ApiError.NonceMismatch -> AuthPollError.NonceMismatch
    ApiError.InvalidSecret -> AuthPollError.InvalidSecret
    ApiError.RateLimited -> AuthPollError.RateLimited
    is ApiError.Network -> AuthPollError.NetworkUnavailable
    // Остальное (Unauthorized, AccountBlocked, Conflict, …) на пути входа
    // означает неожиданный ответ сервера, а не отдельное состояние.
    else -> AuthPollError.Unexpected
}
