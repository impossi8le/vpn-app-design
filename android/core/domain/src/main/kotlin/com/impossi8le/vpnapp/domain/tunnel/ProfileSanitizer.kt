package com.impossi8le.vpnapp.domain.tunnel

import com.impossi8le.vpnapp.domain.tunnel.CoreConfig.Companion.PEM_PRINTING_VERB
import com.impossi8le.vpnapp.domain.tunnel.CoreConfig.Companion.SAFE_MAX_VERB

/**
 * Правка текста профиля перед передачей ядру.
 *
 * **Зачем это вообще нужно.** У `ClientAPI_Config` ядра OpenVPN 3 нет поля
 * уровня логирования: `verb` читается ядром **из текста профиля**. То есть
 * боевой профиль со строкой `verb 3` заставляет ядро печатать тела PEM — а там
 * приватный ключ — в logcat. Тип `CoreConfig` тут бессилен: он не участвует в
 * разборе текста.
 *
 * Поэтому уровень выправляется **в тексте**, и это чистая функция: её можно
 * проверить на JVM, без ядра и без устройства.
 *
 * Что НЕ делается: профиль не переписывается целиком и ключи не трогаются.
 * Меняются ровно строки `verb`; всё остальное остаётся байт в байт, иначе легко
 * случайно испортить блок PEM или поменять смысл маршрутизации.
 */
object ProfileSanitizer {

    private const val VERB = "verb"

    /**
     * Привести `verb` к безопасному значению.
     *
     * Дубликаты директивы **удаляются**: ядро взяло бы последнее значение, и
     * второй `verb 3` в конце профиля обошёл бы нашу правку. Оставляем первое.
     */
    fun sanitizeVerb(profileText: String): SanitizedProfile {
        val lines = profileText.split('\n')
        var originalVerb: Int? = null
        var verbSeen = false
        val result = ArrayList<String>(lines.size)

        for (line in lines) {
            val parsed = parseVerb(line)
            if (parsed == null) {
                result += line
                continue
            }
            if (verbSeen) continue // дубликат: ядро не должно увидеть второе значение
            verbSeen = true
            originalVerb = parsed
            result += if (parsed > SAFE_MAX_VERB) "$VERB $SAFE_MAX_VERB" else line
        }

        return SanitizedProfile(
            text = result.joinToString("\n"),
            originalVerb = originalVerb,
            lowered = originalVerb?.let { it > SAFE_MAX_VERB } ?: false,
        )
    }

    /**
     * Разобрать строку `verb N`, если это она.
     *
     * Учитываются ведущие пробелы и хвостовой комментарий (`verb 3 # подробно`):
     * в реальных профилях встречается и то и другое. Возвращает `null`, если
     * строка уровня не задаёт.
     */
    private fun parseVerb(line: String): Int? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("$VERB ")) return null
        val rest = trimmed.removePrefix("$VERB ").trim()
        if (rest.isEmpty()) return null
        val number = rest.substringBefore(' ').substringBefore('\t')
        return number.toIntOrNull()
    }
}

/**
 * Результат правки.
 *
 * [originalVerb] и [lowered] нужны для журнала: факт понижения уровня стоит
 * записать, а сам профиль — нет.
 */
data class SanitizedProfile(
    val text: String,
    val originalVerb: Int?,
    val lowered: Boolean,
) {
    /** Был ли профиль опасным до правки. */
    val wasUnsafe: Boolean get() = originalVerb != null && originalVerb >= PEM_PRINTING_VERB
}
