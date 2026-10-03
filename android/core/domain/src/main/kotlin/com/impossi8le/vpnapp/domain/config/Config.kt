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

    // Специально НЕ переопределяем toString: он печатает содержимое профиля,
    // в котором лежит приватный ключ. Дефолтный toString печатает только хеш.
}

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
 * без рабочего профиля (см. §7 архитектуры).
 */
interface ProfileStore {
    /** Текущий профиль или `null`, если его нет. */
    fun load(): Profile?

    /** Подготовить без применения. Бросает, если конфиг не прошёл валидацию. */
    fun stage(raw: ByteArray): StagedProfile

    /** Применить подготовленный профиль. */
    fun commit(staged: StagedProfile)

    /** Вернуть предыдущую рабочую версию. */
    fun rollback()

    /** Удалить профиль целиком — при выходе из аккаунта. */
    fun clear()
}

/** Получение профиля с сервера. */
interface ConfigService {
    /** Скачать `.ovpn` по публичному коду. Бросает типизированную ошибку. */
    suspend fun fetch(publicCode: String): ByteArray
}
