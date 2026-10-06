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

/**
 * Почему опрос входа мог не состояться.
 *
 * Отдельный тип в домене, а не исключение/`ApiError` из `core:network`: у
 * `feature:auth` нет и не должно быть зависимости на сетевой модуль (§1 —
 * «граф зависимостей направлен вниз»), а различать эти случаи он обязан.
 * Ошибка сети — «нет связи», [NonceMismatch] — «код не совпал, введите заново»;
 * свести их к одному `Exception` значит показать неверную причину и не вернуть
 * пользователя к вводу кода.
 */
sealed interface AuthPollError {
    /**
     * Код не совпал (`403 nonce_mismatch`). Операция ЖИВА: тот же
     * `public_code` и `secret` ещё годны, можно ввести код заново — вход
     * начинать сначала не нужно.
     */
    data object NonceMismatch : AuthPollError

    /**
     * Секрет не сошёлся (`401 invalid_secret`): операция испорчена, нужен
     * новый вход. Отличается от [NonceMismatch] — здесь код вводить заново
     * бессмысленно.
     */
    data object InvalidSecret : AuthPollError

    /** Слишком часто (`429`): повтор осмыслен после паузы. */
    data object RateLimited : AuthPollError

    /** Сеть недоступна или таймаут: повтор осмыслен. */
    data object NetworkUnavailable : AuthPollError

    /** Прочее: тело не разобралось или сервер ответил неожиданно. */
    data object Unexpected : AuthPollError
}

/**
 * Обёртка, чтобы протащить типизированную ошибку через `Result`.
 *
 * `Result` умеет нести только `Throwable`, а вызывающему нужен тип, по которому
 * он примет решение. Тот же приём, что у `ConfigFetchException` (§4.3).
 */
class AuthPollException(val error: AuthPollError) :
    Exception("не удалось опросить вход: $error")

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
