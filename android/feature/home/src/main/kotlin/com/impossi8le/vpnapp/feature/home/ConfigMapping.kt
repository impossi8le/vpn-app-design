package com.impossi8le.vpnapp.feature.home

import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Преобразование серверного подключения в строку списка.
 *
 * Вынесено из экрана отдельной чистой функцией по той же причине, что и
 * `presentation()`: решение «как выглядит строка» — это логика, и её надо
 * проверять тестом, а не рендером. Экран не должен решать, что значит
 * `SubscriptionStatus`: он получает готовую строку.
 *
 * **Почему время приходит параметром, а не берётся внутри.** Момент «сейчас»
 * влияет на подпись «осталось N дней», и если читать часы внутри, тест перестанет
 * быть детерминированным, а функция — чистой. Передавая `nowEpochSeconds`, мы
 * получаем один и тот же результат на любом запуске.
 *
 * **Границы с защитой здесь нет и быть не может.** [ConfigRowStatus] описывает
 * состояние ПОДПИСКИ (доступно/истекло), а не туннеля: зелёный статус защиты
 * приходит только из замера (§6), и этот файл к нему отношения не имеет.
 */
fun ConfigSummary.toRowState(nowEpochSeconds: Long): ConfigRowState {
    // Действующая подписка сейчас всегда «Доступно», а не «Выбрано»/«Подключено»:
    // выбор и факт поднятого туннеля — это состояние приложения, которого в
    // ответе сервера нет. Приписать их здесь значило бы выдумать данные.
    val alive = status == SubscriptionStatus.ACTIVE
    return ConfigRowState(
        id = id,
        name = name,
        countryCode = countryCode,
        subtitle = if (alive) {
            val days = daysLeft(endDateEpochSeconds, nowEpochSeconds)
            "до ${formatDate(endDateEpochSeconds)} · осталось $days ${pluralDays(days)}"
        } else {
            // Истёкшее не «работает до …» — оно уже кончилось. Формулировка из
            // макета («Истёк 30.09.2026») прямо говорит, что выбирать нечего.
            "Истёк ${formatDate(endDateEpochSeconds)}"
        },
        status = if (alive) ConfigRowStatus.Available else ConfigRowStatus.Expired,
    )
}

/**
 * Преобразовать весь список, сохранив порядок сервера.
 *
 * Порядок не сортируем: сортировка — решение представления, и если понадобится
 * поднять действующие наверх, это должно быть видно здесь явно, а не тонуть в
 * отображении.
 */
fun List<ConfigSummary>.toRowStates(nowEpochSeconds: Long): List<ConfigRowState> =
    map { it.toRowState(nowEpochSeconds) }

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

private fun formatDate(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DATE_FORMAT)

/** Целых дней до окончания; отрицательное значение (просрочка) прижимаем к нулю. */
private fun daysLeft(endEpochSeconds: Long, nowEpochSeconds: Long): Long =
    ((endEpochSeconds - nowEpochSeconds) / SECONDS_PER_DAY).coerceAtLeast(0L)

/**
 * Русское склонение слова «день».
 *
 * Не украшение: «осталось 1 дней» читается как сбой, и именно на таких мелочах
 * интерфейс теряет доверие. Правила стандартные (11–14 — исключение).
 */
private fun pluralDays(days: Long): String {
    val tail10 = days % 10
    val tail100 = days % 100
    return when {
        tail10 == 1L && tail100 != 11L -> "день"
        tail10 in 2L..4L && tail100 !in 12L..14L -> "дня"
        else -> "дней"
    }
}

private const val SECONDS_PER_DAY = 86_400L
