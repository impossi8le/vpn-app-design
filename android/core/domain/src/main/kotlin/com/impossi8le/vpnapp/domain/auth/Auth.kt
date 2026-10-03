package com.impossi8le.vpnapp.domain.auth

/**
 * Вход через Telegram-бота: приложение открывает бота, бот присылает обратно
 * `publicCode`, приложение опрашивает сервер до готовности сессии.
 *
 * `op` — привязка операции: генерируется приложением и передаётся в ссылке,
 * чтобы чужой или устаревший callback можно было отбросить. Без неё сторонний
 * APK, перехвативший кастомную схему, мог бы подсунуть свой код
 * (см. §8 и открытый вопрос §14 про App Links).
 */
data class LoginChallenge(val publicCode: String, val op: String)

data class Session(val token: String, val expiresAtEpochSeconds: Long)

sealed interface PollResult {
    data class Pending(val retryAfterSeconds: Int) : PollResult
    data class Ready(val session: Session) : PollResult
    data class Expired(val reason: String) : PollResult
}

sealed interface AuthState {
    data object SignedOut : AuthState
    data class SignedIn(val session: Session) : AuthState
}

/** Хранение сессии. Реализация — EncryptedSharedPreferences в core:security. */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}

interface AuthService {
    /** Начать вход: получить публичный код и `op` для ссылки в бота. */
    suspend fun startLogin(): LoginChallenge

    /** Опросить состояние. `Pending` не ошибка — это штатный ответ до готовности. */
    suspend fun pollSession(op: String): PollResult
}
