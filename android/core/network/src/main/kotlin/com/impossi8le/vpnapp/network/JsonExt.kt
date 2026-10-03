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
