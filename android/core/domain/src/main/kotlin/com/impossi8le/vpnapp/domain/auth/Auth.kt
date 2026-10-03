package com.impossi8le.vpnapp.domain.auth

/**
 * Вход через Telegram-бота. Точная механика — контракт §1–2.
 *
 * Ключевой момент: `publicCode` виден в ссылке и в чате, поэтому сам по себе
 * сессии не даёт. Сессию даёт знание `secret` — он генерируется приложением и
 * НИКОГДА не покидает устройство в открытом виде, на сервер уходит только его
 * SHA-256. Плюс `deviceNonce`, который бот показывает пользователю и который тот
 * вводит руками: он замыкает подтверждение на устройство, инициировавшее вход, и
 * закрывает login-CSRF (злоумышленник знает свой `secret`, но не знает nonce,
 * отправленный в чужой чат).
 */
data class LoginChallenge(
    val publicCode: String,
    /** Генерируется приложением здесь и остаётся на устройстве. */
    val secret: String,
    val deepLink: String,
    val expiresAtEpochSeconds: Long,
)

data class Session(val token: String, val expiresAtEpochSeconds: Long)

/** Исход опроса. Терминальные состояния — это НЕ ошибки транспорта. */
sealed interface PollOutcome {
    data class Pending(val retryAfterMillis: Long) : PollOutcome
    data class Confirmed(val session: Session, val chatId: Long) : PollOutcome
    data object Expired : PollOutcome
    data object Denied : PollOutcome
    data object AttemptLimitExceeded : PollOutcome

    /**
     * Сессия уже была выдана (потерянный ответ). Отдельного эндпоинта повторной
     * выдачи нет — клиент предлагает войти заново, а не блокируется навсегда.
     */
    data object AlreadyConsumed : PollOutcome
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
    /** Начать вход: получить публичный код и ссылку, `secret` уже внутри. */
    suspend fun startLogin(deviceName: String): Result<LoginChallenge>

    /** Опросить состояние. `Pending` — штатный ответ, а не ошибка. */
    suspend fun pollSession(publicCode: String, secret: String, deviceNonce: String): Result<PollOutcome>
}
