package com.impossi8le.vpnapp.update

import java.io.File

private const val LATEST_KEY = "latest="
private const val MIN_KEY = "min="

/**
 * Последний известный вердикт проверки обновления, переживающий перезапуск.
 *
 * **Зачем.** Блокировка обязана действовать с первого кадра. Проверка идёт по
 * сети и занимает время; если решение о блокировке ждёт её, то на холодном
 * старте приложение успевает показать рабочий экран — и в это окно им можно
 * пользоваться. Проверено на телефоне: экран подключения открывался, пока
 * проверка ещё шла, то есть «заставить обновиться» не получалось.
 *
 * Хранится две цифры, а не готовый вердикт: `latest` нужен, чтобы показать
 * «Требуется 1.0.N» до того, как сеть ответит, `min` — чтобы понять, блокировать
 * ли. Файл читается и пишется на главном потоке, но он крошечный — это дешевле,
 * чем отдельная асинхронность вокруг первого кадра.
 *
 * Не шифруется: это не секрет, а публичный номер версии. Локальные настройки
 * здесь держать незачем — при смене порога сервер главнее.
 */
class UpdateVerdictStore(private val file: File) {

    /** `latest` — номер последней известной версии; `null`, если ещё не проверяли. */
    data class Verdict(val latest: Int, val minSupported: Int?)

    fun read(): Verdict? {
        if (!file.exists()) return null
        return try {
            val lines = file.readLines()
            val latest = lines.firstOrNull { it.startsWith(LATEST_KEY) }
                ?.removePrefix(LATEST_KEY)?.trim()?.toIntOrNull() ?: return null
            val min = lines.firstOrNull { it.startsWith(MIN_KEY) }
                ?.removePrefix(MIN_KEY)?.trim()?.toIntOrNull()
            Verdict(latest = latest, minSupported = min)
        } catch (e: Exception) {
            // Битый или недочитанный файл — это «не знаем», а не повод падать:
            // вердикт восстановится следующей проверкой.
            null
        }
    }

    fun write(latest: Int, minSupported: Int?) {
        try {
            file.writeText("$LATEST_KEY$latest\n$MIN_KEY${minSupported ?: ""}\n")
        } catch (e: Exception) {
            // Не записалось — не беда: сеть решит при следующем запуске.
        }
    }
}
