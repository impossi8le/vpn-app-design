package com.impossi8le.vpnapp.domain.update

private const val TAG_PREFIX = "android-v"

/**
 * Номер сборки из тега релиза. `null` — тег не нашего формата.
 *
 * Релиз выпускается тегом `android-v<versionCode>` (см. CI). Если формат не
 * совпал, сравнивать не с чем: вернуть «обновление есть» здесь означало бы
 * предложить пользователю скачать то, о чём мы ничего не знаем.
 */
fun parseReleaseTag(tag: String): Int? {
    if (!tag.startsWith(TAG_PREFIX)) return null
    val digits = tag.removePrefix(TAG_PREFIX)
    if (digits.isEmpty() || digits.any { !it.isDigit() }) return null
    return digits.toIntOrNull()
}

/** Что известно о свежести сборки. */
sealed interface UpdateStatus {
    /** Установлена последняя версия. */
    data object UpToDate : UpdateStatus

    /** Есть версия новее установленной. */
    data class Available(val versionCode: Int) : UpdateStatus

    /** Ответить не удалось: релиза нет, тег чужой, проверка не прошла. */
    data object Unknown : UpdateStatus
}

/**
 * Нужно ли обновление.
 *
 * `latestTag == null` — проверка не состоялась; это `Unknown`, а не
 * `Available`: тревожить пользователя баннером по несостоявшейся проверке нельзя.
 */
fun updateStatus(currentVersionCode: Int, latestTag: String?): UpdateStatus {
    val latest = latestTag?.let(::parseReleaseTag) ?: return UpdateStatus.Unknown
    return if (latest > currentVersionCode) {
        UpdateStatus.Available(latest)
    } else {
        UpdateStatus.UpToDate
    }
}
