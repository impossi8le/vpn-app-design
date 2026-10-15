package com.impossi8le.vpnapp.domain.diagnostics

/**
 * Обезличивание профиля перед отправкой в диагностическом отчёте.
 *
 * Ветка D (Task 10) полагается на фактическое поведение: пакет по кнопке обязан
 * сохранить структуру подключения и НЕ содержать ключевого материала. Здесь
 * реализована та же логика, что в Task 2 плана (`2026-10-13-error-reporting-client.md`).
 *
 * Вырезаются ровно блоки ключевого материала (`<ca>`, `<cert>`, `<key>`,
 * `<tls-auth>`, `<tls-crypt>`), а остальные директивы остаются. Теги удаляются
 * вместе с содержимым: пустой `<key></key>` читался бы как испорченный профиль.
 */
object ConfigAnonymizer {

    /** Блоки, целиком подлежащие удалению: ключи и сертификаты. */
    private val SENSITIVE_TAGS = listOf("ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2")

    fun anonymize(profileText: String): String {
        val lines = profileText.split('\n')
        val result = ArrayList<String>(lines.size)
        var removedTag: String? = null

        for (line in lines) {
            val trimmed = line.trim()

            if (removedTag != null) {
                // Мы внутри вырезаемого блока — ждём закрывающий тег.
                if (trimmed.equals("</$removedTag>", ignoreCase = true)) {
                    result += "# <$removedTag> удалён (ключевой материал)"
                    removedTag = null
                }
                continue
            }

            val opening = openingSensitiveTag(trimmed)
            if (opening != null) {
                removedTag = opening
                continue
            }

            result += line
        }

        return result.joinToString("\n")
    }

    /** Имя открывающего чувствительного тега, если строка — именно он. */
    private fun openingSensitiveTag(trimmed: String): String? {
        if (!trimmed.startsWith("<") || trimmed.startsWith("</")) return null
        val name = trimmed.removePrefix("<").substringBefore('>').trim().lowercase()
        return name.takeIf { it in SENSITIVE_TAGS }
    }
}
