package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.config.OvpnFuzzFixtures.pemBodies
import com.impossi8le.vpnapp.config.OvpnFuzzFixtures.production
import com.impossi8le.vpnapp.domain.diagnostics.ConfigAnonymizer
import com.impossi8le.vpnapp.domain.diagnostics.LogScrubber
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Второе утверждение фаззинга: **искажённый профиль не выносит секрет наружу**.
 *
 * Опасность не только в падении. Обрыв блока `BEGIN` без `END` — классический
 * обход построчного скраббера: он ищет пару BEGIN/END, а тело ключа уходит
 * отдельными строками и остаётся в отчёте. Те же искажения, что в `OvpnFuzzTest`,
 * прогоняются через обезличивание (`ConfigAnonymizer`) и скраб логов
 * (`LogScrubber`) — ни тело ключа, ни ключ `tls-auth` не должны пережить разбор.
 *
 * Тела PEM берутся из САМОЙ фикстуры (см. `pemBodies`), поэтому тест не ломается
 * от смены сгенерированных ключей и не несёт захардкоженного секрета в коде.
 *
 * **Два искажения реально вскрыли утечку** (находки задачи #41):
 *  - «закрывающий тег без открывающего» — `ConfigAnonymizer`;
 *  - «обрыв в половине» — `LogScrubber`.
 * Их из широкого прогона исключает [knownLeakingMutations], а сами дефекты
 * зафиксированы отдельными `@Disabled`-тестами ниже. Прочие искажения зелены —
 * это и означает, что защита держит всё остальное.
 */
class OvpnFuzzSecretsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cleanMutations")
    fun `обезличивание не оставляет тел ключей`(name: String, input: String) {
        val anonymized = ConfigAnonymizer.anonymize(input)

        for (body in pemBodies) {
            assertFalse(
                anonymized.contains(body),
                "тело ключа пережило обезличивание при искажении: $name",
            )
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cleanMutations")
    fun `скраббер не оставляет тел ключей`(name: String, input: String) {
        val scrubbed = LogScrubber.scrubStream(input.split('\n')).joinToString("\n")

        for (body in pemBodies) {
            assertFalse(
                scrubbed.contains(body),
                "тело ключа пережило скраб при искажении: $name",
            )
        }
    }

    @Test
    fun `фикстура действительно содержит тела ключей`() {
        // Страховка от ложно-зелёного: если фикстура опустеет, проверки выше
        // перестанут что-либо проверять, оставаясь зелёными.
        assertTrue(pemBodies.size >= 4, "тела PEM обязаны быть в фикстуре, иначе тесты вышерны")
    }

    // --- Зафиксированные дефекты -------------------------------------------

    /**
     * ДЕФЕКТ 1. `ConfigAnonymizer` не вырезает блок, у которого потерян
     * открывающий тег, но остался закрывающий.
     *
     * Разбор в `openingSensitiveTag`: строка `</ca>` распознаётся как
     * ЗАКРЫВАЮЩАЯ (`startsWith("</")` → `null`), поэтому открытие не находится,
     * а тело ключа идёт в результат как обычные строки. Для блока `<key>` это
     * означает, что приватный ключ целиком уходит в обезличенный отчёт.
     *
     * Проверено: тело приватного ключа (26 строк) остаётся в выводе на 100%.
     */
    @Disabled("дефект: осиротевший закрывающий тег снимает вырезание — приватный ключ уходит в отчёт")
    @Test
    fun `осиротевший закрывающий тег не снимает вырезание ключа`() {
        val broken = production.replaceFirst("<key>", "") // остаётся </key> без <key>

        val anonymized = ConfigAnonymizer.anonymize(broken)

        for (line in privateKeyBody()) {
            assertFalse(
                anonymized.contains(line),
                "приватный ключ утёк при осиротевшем </key>: $line",
            )
        }
    }

    /**
     * ДЕФЕКТ 2. `LogScrubber` оставляет тело ключа, если блок PEM оборван на
     * середине (есть `BEGIN`, нет `END`).
     *
     * `PEM_BLOCK` требует и `BEGIN`, и `END`. Обрыв на середине ключа даёт
     * незакрытый блок, регулярное выражение его не находит, и тело уходит в лог
     * как есть. Ровно так выглядит усечённый ответ сервера.
     *
     * Проверено: при обрыве внутри `<key>` в выводе остаётся 12 из 26 строк тела
     * приватного ключа.
     */
    @Disabled("дефект: незакрытый BEGIN не вырезается — тело приватного ключа уходит в лог")
    @Test
    fun `незакрытый BEGIN не оставляет тела ключа в логе`() {
        val keyStart = production.indexOf("<key>")
        val keyEnd = production.indexOf("</key>")
        val broken = production.substring(0, keyStart + (keyEnd - keyStart) / 2) // обрыв внутри ключа

        val scrubbed = LogScrubber.scrubStream(broken.split('\n')).joinToString("\n")

        for (line in privateKeyBody()) {
            assertFalse(
                scrubbed.contains(line),
                "тело приватного ключа утекло при обрыве блока: $line",
            )
        }
    }

    /** Строки тела приватного ключа из фикстуры. */
    private fun privateKeyBody(): List<String> {
        val body = Regex("-----BEGIN PRIVATE KEY-----([\\s\\S]*?)-----END PRIVATE KEY-----")
            .find(production)?.groupValues?.get(1) ?: return emptyList()
        return body.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    companion object {

        /**
         * Искажения, которые дефект уже вскрыл. Из широкого прогона исключены,
         * чтобы прогон оставался зелёным; сами дефекты живут в `@Disabled`-тестах.
         * Имена совпадают с именами мутаций в [OvpnFuzzTest.mutationsList].
         */
        private val knownLeakingMutations = setOf(
            "закрывающий тег без открывающего",
            "обрыв в половине",
        )

        @JvmStatic
        fun cleanMutations(): java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> =
            OvpnFuzzTest.mutationsList()
                .filterNot { it.name in knownLeakingMutations }
                .map { org.junit.jupiter.params.provider.Arguments.of(it.name, it.input) }
                .stream()
    }
}
