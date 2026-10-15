package com.impossi8le.vpnapp.domain.diagnostics

/**
 * Строка как значение JSON, вместе с кавычками.
 *
 * Нужна там, где JSON собирается склейкой (`meta` отчёта): экранирование «руками»
 * рано или поздно забывают, а модель телефона — текст из системы, и кавычка в нём
 * сломала бы тело запроса. Отдельная чистая функция, чтобы это проверялось тестом,
 * а не вниманием.
 *
 * Полноценный `Json.encodeToString(String.serializer(), …)` сделал бы то же, но
 * тянет сериализацию туда, где больше нечего сериализовать.
 */
fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    for (ch in value) {
        when (ch) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            // Управляющие символы обязаны уходить в \uXXXX: в JSON они недопустимы
            // как есть, и тело стало бы неразбираемым.
            in '\u0000'..'\u001F' -> append("\\u%04x".format(ch.code))
            else -> append(ch)
        }
    }
    append('"')
}
