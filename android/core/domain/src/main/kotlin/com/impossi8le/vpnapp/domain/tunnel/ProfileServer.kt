package com.impossi8le.vpnapp.domain.tunnel

/**
 * Адрес сервера из текста профиля.
 *
 * Нужен диагностике: адрес входит и в отчёт поддержке, и в авто-метрики, а
 * «неизвестно» — честная, но бесполезная строка. Читать `.ovpn` из UI ради неё
 * нельзя: в файле приватный ключ, и открывать его лишний раз незачем. Поэтому
 * адрес извлекается ОДИН раз, там, где текст профиля уже есть на руках (при
 * сохранении), и кладётся рядом с метаданными.
 *
 * Разбор намеренно узкий: только директива `remote`. Всё остальное — дело ядра,
 * и дублировать его разбор здесь не нужно.
 */
object ProfileServer {

    /**
     * `host:port` из первой директивы `remote`, или `null`, если её нет.
     *
     * Первый `remote`, а не последний: без `remote-random` ядро берёт первый.
     * При `remote-random` адрес в отчёте — один из списка, и это честнее, чем
     * пустая строка.
     */
    fun of(profileText: String): String? {
        for (rawLine in profileText.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            val tokens = line.split(' ', '\t').filter { it.isNotEmpty() }
            // Токенов минимум два: `remote` и хост. `remote-random` и
            // `remote-cert-tls` не подходят по первому токену — сравнение точное,
            // иначе в отчёт попал бы мусор вроде «-random».
            if (tokens.size < 2 || tokens[0] != "remote") continue
            val host = tokens[1]
            val port = tokens.getOrNull(2)?.takeIf { it.all(Char::isDigit) }
            return if (port != null) "$host:$port" else host
        }
        return null
    }
}
