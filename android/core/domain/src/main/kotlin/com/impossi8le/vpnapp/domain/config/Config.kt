package com.impossi8le.vpnapp.domain.config

/**
 * Валидный профиль туннеля. Хранит сырые байты `.ovpn`: домену не нужно знать
 * про формат, он только переносит их между сетью и ядром.
 *
 * Не `data class`: у него автогенерируемый `equals` сравнивает массивы по ссылке,
 * из-за чего два одинаковых по содержимому профиля считались бы разными.
 */
class Profile(val raw: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is Profile && raw.contentEquals(other.raw)

    override fun hashCode(): Int = raw.contentHashCode()

    // toString намеренно не переопределён: он печатал бы содержимое профиля,
    // в котором лежит приватный ключ.
}

/** Состояние подписки на подключение. У каждого подключения своё. */
enum class SubscriptionStatus { ACTIVE, EXPIRED, REVOKED, PENDING }

/**
 * Подключение в списке (контракт §3). Единица списка — конфиг, не страна:
 * на одну страну может быть несколько подключений.
 */
data class ConfigSummary(
    val id: String,
    val name: String,
    val countryCode: String,
    val city: String,
    val startDateEpochSeconds: Long,
    val endDateEpochSeconds: Long,
    val status: SubscriptionStatus,
)

data class ConfigList(val chatId: Long, val configs: List<ConfigSummary>)

/** Результат `stage`: прошёл валидацию, но ещё не применён. */
interface StagedProfile

/**
 * Проверка конфига до применения. Невалидный конфиг не должен доходить до
 * `commit` — иначе рабочий профиль будет затёрт мусором.
 */
fun interface ProfileValidator {
    fun isValid(raw: ByteArray): Boolean
}

/**
 * Хранилище профиля. Реализация живёт в core:config (чистый JVM) и обязана
 * обеспечивать согласованность записи: обрыв в любой точке не оставляет систему
 * без рабочего профиля (§7).
 */
interface ProfileStore {
    fun load(): Profile?

    /** Подготовить без применения. Бросает, если конфиг не прошёл валидацию. */
    fun stage(raw: ByteArray): StagedProfile

    fun commit(staged: StagedProfile)

    /** Вернуть предыдущую рабочую версию. */
    fun rollback()

    /** Удалить профиль целиком — при выходе из аккаунта. */
    fun clear()
}

/**
 * Получение конфигов с сервера (контракт §3–4).
 *
 * `fetchConfig` отдаёт сырые байты и версию с сервера: версия нужна, чтобы
 * пропустить запись, если у клиента она уже есть.
 */
interface ConfigService {
    suspend fun listConfigs(): Result<ConfigList>

    suspend fun fetchConfig(configId: String): Result<FetchedConfig>
}

data class FetchedConfig(
    val raw: ByteArray,
    val version: String,
    val hash: String,
) {
    override fun equals(other: Any?): Boolean =
        other is FetchedConfig && raw.contentEquals(other.raw) &&
            version == other.version && hash == other.hash

    override fun hashCode(): Int = (raw.contentHashCode() * 31 + version.hashCode()) * 31 + hash.hashCode()
}
