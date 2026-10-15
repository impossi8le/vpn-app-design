package com.impossi8le.vpnapp.feature.account

import com.impossi8le.vpnapp.domain.diagnostics.ConfigAnonymizer
import com.impossi8le.vpnapp.domain.diagnostics.LogScrubber

/**
 * Пакет, который уходит на сервер по кнопке.
 *
 * [text] — существующий текстовый отчёт поддержке, [logs] — лог ядра, [config] —
 * обезличенный профиль. Пустая строка означает «части нет»: `null` в multipart
 * привёл бы к пустой части, а сервер прочитал бы её как «файл есть, но пустой».
 */
data class CollectedReport(
    val text: String,
    val logs: String,
    val config: String,
)

/**
 * Сборка отчёта по кнопке.
 *
 * Обезличивание конфига и скраб логов делаются ЗДЕСЬ, а не на сервере: приватный
 * ключ не должен покидать устройство даже в обезличенном виде, а серверу нельзя
 * доверять вырезание секретов из недоверенного входа.
 *
 * Чистая функция: тот же вход даёт тот же результат, поэтому проверяется на JVM.
 */
object ReportCollector {

    fun build(reportText: String, logs: List<String>, profileText: String?): CollectedReport =
        CollectedReport(
            text = reportText,
            logs = LogScrubber.scrubStream(logs).joinToString("\n"),
            config = profileText?.let(ConfigAnonymizer::anonymize).orEmpty(),
        )
}
