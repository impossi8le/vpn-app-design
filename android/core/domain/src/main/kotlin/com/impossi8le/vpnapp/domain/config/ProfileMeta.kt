package com.impossi8le.vpnapp.domain.config

/**
 * Что установлено на устройстве и до каких пор это годно.
 *
 * Лежит рядом с профилем, а не на сервере: клиент не спрашивает «нет ли версии
 * новее», он решает по локальным данным, нужен ли вообще запрос.
 */
data class ProfileMeta(
    val configId: String,
    val version: String,
    val endDateEpochSeconds: Long,
    /**
     * Адрес сервера (`host:port`) из этого профиля.
     *
     * Лежит здесь, а не читается из `.ovpn` по требованию: в профиле приватный
     * ключ, и открывать его ради строки диагностики незачем. Заполняется один
     * раз при сохранении — там текст профиля уже на руках. `null` у профилей,
     * сохранённых до появления поля, и когда `remote` в профиле не нашёлся.
     */
    val serverHostPort: String? = null,
)

/** Хранилище метаданных профиля. Реализация — рядом с профилем, в core:config. */
interface ProfileMetaStore {
    fun load(): ProfileMeta?
    fun save(meta: ProfileMeta)
    fun clear()
}

enum class ProfileUse { Missing, Expired, Reusable }

/**
 * Нужен ли запрос к серверу, чтобы получить профиль.
 *
 * Чистая функция: тот же набор входов даёт тот же ответ на любом запуске, поэтому
 * её проверяют тестом, а не сетью. Запрос нужен (`Missing`), когда профиля нет,
 * метаданных нет или запрошен другой конфиг. `Expired` — профиль недействителен
 * по сроку: его надо удалить. `Reusable` — можно поднимать из файла без сети.
 */
fun decideProfileUse(
    hasProfile: Boolean,
    meta: ProfileMeta?,
    requestedConfigId: String,
    nowEpochSeconds: Long,
): ProfileUse = when {
    !hasProfile -> ProfileUse.Missing
    meta == null -> ProfileUse.Missing
    meta.configId != requestedConfigId -> ProfileUse.Missing
    meta.endDateEpochSeconds <= nowEpochSeconds -> ProfileUse.Expired
    else -> ProfileUse.Reusable
}
