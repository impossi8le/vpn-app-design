package com.impossi8le.vpnapp.feature.account

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Список лицензий должен быть полон и честен.
 *
 * Тест держит три обещания.
 *
 * Первое — **полнота**. Ядро OpenVPN 3 и шесть его зависимостей перечислены
 * (issue #24). Если компонент случайно выпадет из списка, уведомление о нём
 * исчезнет молча — а именно этого не было нигде раньше.
 *
 * Второе — **ядро под MPL 2.0**. Лицензия ядра названа именно так.
 *
 * Третье — **§6**. Список лицензий говорит о правовых условиях стороннего кода,
 * а не о защите. Ни одного слова «защищено»/«безопасно» в текстах быть не
 * должно: это не то, что лицензии обещают.
 */
class ThirdPartyLicensesTest {

    private val allTexts = buildList {
        add(ENGINE_COMPONENT_NAME)
        add(ENGINE_COMPONENT_LICENSE)
        add(ENGINE_COMPONENT_SUMMARY)
        THIRD_PARTY_COMPONENTS.forEach { add(it.name); add(it.license); add(it.summary) }
        add(LICENSES_FOOTNOTE)
    }

    @Test
    fun `ядро названо под MPL 2_0`() {
        assertEquals("MPL 2.0", ENGINE_COMPONENT_LICENSE)
        assertTrue(ENGINE_COMPONENT_NAME.contains("OpenVPN 3"))
    }

    @Test
    fun `перечислены все зависимости из issue`() {
        val names = THIRD_PARTY_COMPONENTS.map { it.name.lowercase() }

        // Перечень из issue #24: asio, mbedTLS, OpenSSL, lz4, fmt, jsoncpp.
        // lzo здесь НЕТ намеренно: в собранной библиотеке нет символов
        // библиотеки Oberhumer — используется встроенная распаковка ядра.
        listOf("asio", "mbedtls", "openssl", "lz4", "fmt", "jsoncpp").forEach { expected ->
            assertTrue(
                names.any { it == expected },
                "зависимость «$expected» должна быть в списке лицензий",
            )
        }
    }

    @Test
    fun `ни у одного компонента не пустая лицензия`() {
        THIRD_PARTY_COMPONENTS.forEach { component ->
            assertTrue(component.name.isNotBlank(), "у компонента должно быть имя")
            assertTrue(component.license.isNotBlank(), "${component.name}: должна быть лицензия")
            assertTrue(component.summary.isNotBlank(), "${component.name}: должно быть пояснение")
        }
    }

    @Test
    fun `список лицензий не обещает защиту`() {
        // §6: лицензии — правовой факт, а не обещание защиты.
        val forbidden = listOf("защищено", "защищен", "безопасно", "аноним")
        val joined = allTexts.joinToString(" ").lowercase()

        forbidden.forEach { word ->
            assertFalse(joined.contains(word), "список лицензий не должен обещать «$word»")
        }
    }

    @Test
    fun `текст от ядра показывается как есть`() {
        val notice = engineLicenseNotice("Copyright (C) 2012- OpenVPN Inc.", "недоступно")

        assertEquals(
            EngineLicenseNotice.Text("Copyright (C) 2012- OpenVPN Inc."),
            notice,
        )
    }

    @Test
    fun `при недоступном ядре показывается причина, а не выдуманный текст`() {
        val notice = engineLicenseNotice(null, "движок недоступен на этом устройстве")

        assertEquals(
            EngineLicenseNotice.Unavailable("движок недоступен на этом устройстве"),
            notice,
        )
    }

    @Test
    fun `пустой текст ядра не превращается в пустое уведомление`() {
        // Пустая строка на месте уведомления читалась бы как «лицензия не нужна».
        assertEquals(
            EngineLicenseNotice.Unavailable("ядро не вернуло текст"),
            engineLicenseNotice("   ", "ядро не вернуло текст"),
        )
    }
}
