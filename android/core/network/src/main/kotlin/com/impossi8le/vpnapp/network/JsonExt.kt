package com.impossi8le.vpnapp.network

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Чтение полей ответа без падений на неполных данных.
 *
 * Контракт предписывает ISO-8601 UTC, но полагаться на это в парсере нельзя:
 * кривой ответ сервера должен давать «поля нет», а не исключение, иначе одна
 * опечатка на бэкенде роняет экран вместо понятного сообщения. Неразборчивая
 * дата превращается в 0 — вызывающий сам решает, что с этим делать.
 */
internal fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.content

internal fun JsonObject.long(key: String): Long? =
    this[key]?.jsonPrimitive?.longOrNull

internal fun JsonObject.instant(key: String): Long {
    val raw = str(key) ?: return 0L
    return try {
        Instant.parse(raw).epochSecond
    } catch (_: DateTimeParseException) {
        0L
    }
}

/**
 * Дата или `null`, если поля нет либо оно не разобралось.
 *
 * Отличие от [instant] существенное, и оно стоило отлаженного часа: `instant`
 * на отсутствующем поле возвращает `0L` — «первое января 1970». Для срока жизни
 * сессии это означало «истекла сорок лет назад», и клиент молча выходил из
 * только что полученной сессии. Ноль выглядел правдоподобно, поэтому причину
 * не было видно ни в логе, ни на экране.
 *
 * Там, где ноль — не осмысленное значение (сроки, даты), нужен этот вариант:
 * вызывающий обязан явно решить, что делать с отсутствием поля.
 */
internal fun JsonObject.instantOrNull(key: String): Long? {
    val raw = str(key) ?: return null
    return try {
        Instant.parse(raw).epochSecond
    } catch (_: DateTimeParseException) {
        null
    }
}
