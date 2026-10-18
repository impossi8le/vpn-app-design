package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.diagnostics.ConfigAnonymizer
import com.impossi8le.vpnapp.domain.diagnostics.LogScrubber
import com.impossi8le.vpnapp.domain.tunnel.ProfileSanitizer
import com.impossi8le.vpnapp.domain.tunnel.ProfileServer
import com.impossi8le.vpnapp.config.OvpnFuzzFixtures.production
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.stream.Stream

/**
 * Главное утверждение фаззинга: **любой мусор на входе — не падение**.
 *
 * Парсер либо разбирает искажённый профиль, либо отказывает понятным образом;
 * исключения, зависания и `Error` из JVM (вроде `StackOverflowError` на глубокой
 * вложенности) недопустимы ни при каком входе. Каждый прогон идёт по всем
 * разборщикам профиля сразу: `ProfileSanitizer`, `ProfileServer`,
 * `ConfigAnonymizer`, `LogScrubber` и «ворота» записи `FileProfileStore.stage`.
 *
 * Источник искажений — `mutations()`: он же используется в тестах секретов.
 */
class OvpnFuzzTest {

    @TempDir
    lateinit var dir: File

    private fun store() = FileProfileStore(dir) { raw -> String(raw).contains("client") }

    /**
     * Прогон одного входа через все разборщики. Ни одно обращение не должно
     * бросить: если бросит — тест упадёт на этом вызове, и в отчёте будет
     * видно, какое именно искажение сломало какой разбор.
     */
    private fun exerciseEveryParser(input: String) {
        ProfileSanitizer.sanitizeVerb(input)
        ProfileServer.of(input)
        ConfigAnonymizer.anonymize(input)
        LogScrubber.scrub(input)
        LogScrubber.scrubStream(input.split('\n'))
        // Ворота записи: невалидное обязано бросить IllegalArgumentException —
        // это ожидаемый отказ, а не падение; всё прочее исключение — дефект.
        try {
            store().stage(input.toByteArray())
        } catch (_: IllegalArgumentException) {
            // ожидаемо: так валидатор говорит «не профиль».
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    fun `искажённый профиль не роняет разбор`(name: String, input: String) {
        exerciseEveryParser(input)
    }

    companion object {

        /** Одно искажение: имя для отчёта и результат применения. */
        data class Mutation(val name: String, val input: String)

        @JvmStatic
        fun mutations(): Stream<Arguments> = mutationsList().stream().map { Arguments.of(it.name, it.input) }

        fun mutationsList(): List<Mutation> {
            val p = production
            val out = ArrayList<Mutation>()

            // --- обрыв ---
            out += Mutation("пустой ввод", "")
            out += Mutation("только перевод строки", "\n")
            out += Mutation("только null-байт", "\u0000")
            out += Mutation("обрыв в половине", OvpnFuzzFixtures.truncated(p, 0.5))
            out += Mutation("обрыв в одном проценте", OvpnFuzzFixtures.truncated(p, 0.01))
            out += Mutation("обрыв после remote", OvpnFuzzFixtures.cutAfterRemote(p))

            // --- незакрытые блоки ---
            out += Mutation("незакрытый <key>", OvpnFuzzFixtures.unterminatedKeyBlock(p))
            out += Mutation("BEGIN без END", OvpnFuzzFixtures.danglingBegin(p))

            // --- мусорные байты ---
            out += Mutation("бинарный мусор", randomBytes(2048))
            out += Mutation("случайный мусор печатный", randomAscii(4096))
            out += Mutation("нулевые байты блоками", "\u0000".repeat(5000))

            // --- гигантские поля ---
            out += Mutation("директива в 1 МБ", "remote " + "a".repeat(1_000_000) + " 1194\n")
            out += Mutation("одна строка в 2 МБ", "x".repeat(2_000_000))
            out += Mutation("миллион пустых строк", "\n".repeat(1_000_000))
            out += Mutation("очень длинное имя тега", "<" + "a".repeat(100_000) + ">")

            // --- отсутствующие директивы ---
            out += Mutation("без remote", p.lineSequence().filterNot { it.startsWith("remote ") || it == "remote-random" }.joinToString("\n"))
            out += Mutation("без блоков PEM", p.substringBefore("<ca>"))
            out += Mutation("только remote", "remote 198.51.100.10 1194 udp\n")

            // --- структурные поломки ---
            out += Mutation("закрывающий тег без открывающего", OvpnFuzzFixtures.orphanCloseTag(p))
            out += Mutation("вложенные блоки", "<ca>\n<key>\n<cert>\n-----BEGIN CERTIFICATE-----\nAAAA\n")
            out += Mutation("тег с мусором внутри", "<ca >\n< ca>\n</ca >\n")
            out += Mutation("директива без значения", "remote\nproto\nverb\n")
            out += Mutation("дубликат verb", "$p\nverb 5\n")

            // --- враждебные строки ---
            out += Mutation("очень глубокая вложенность скобок", "(".repeat(50_000) + ")".repeat(50_000))
            out += Mutation("перевод строки CRLF", p.replace("\n", "\r\n"))
            return out
        }

        private fun randomBytes(n: Int): String {
            val rnd = java.util.Random(42)
            val chars = CharArray(n) { rnd.nextInt(65536).toChar() }
            return String(chars)
        }

        private fun randomAscii(n: Int): String {
            val rnd = java.util.Random(7)
            val sb = StringBuilder(n)
            repeat(n) { sb.append((32 + rnd.nextInt(95)).toChar()) }
            return sb.toString()
        }
    }
}
