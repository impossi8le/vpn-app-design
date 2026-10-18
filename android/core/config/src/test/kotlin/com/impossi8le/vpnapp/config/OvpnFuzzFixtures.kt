package com.impossi8le.vpnapp.config

import org.junit.jupiter.api.Assertions.assertNotNull

/**
 * Основа для фаззинга: анонимизированный боевой профиль и искажения в памяти.
 *
 * Фикстура `anonymized-production.ovpn` — единственный `.ovpn` в git: её ключи
 * сгенерированы заново, адреса — TEST-NET-2 (RFC 5737). Реальные профили в
 * репозиторий не попадают, поэтому ВСЕ искажения делаются строковыми операциями
 * над текстом фикстуры, а не правкой файлов на диске.
 *
 * Тела PEM, которые тут ищутся для проверок, лежат и в фикстуре, и в
 * сгенерированных ею искажениях — поэтому «секрет утёк» проверяется по
 * маркерам, взятым из самого разобранного текста, а не из захардкоженных строк.
 */
object OvpnFuzzFixtures {

    private const val FIXTURE = "anonymized-production.ovpn"

    /** Боевой (анонимизированный) профиль как текст. Загружается один раз. */
    val production: String by lazy {
        val stream = OvpnFuzzFixtures::class.java.classLoader!!.getResourceAsStream(FIXTURE)
        assertNotNull(stream, "фикстура $FIXTURE должна лежать в src/test/resources")
        stream!!.bufferedReader().readText()
    }

    /**
     * Тела PEM из профиля — то, что при утечке считается секретом.
     *
     * Берём именно СТРОКИ ТЕЛА (между BEGIN и END), а не заголовки: заголовок
     * «-----BEGIN …-----» секретом не является и в логах остаётся законно.
     */
    val pemBodies: List<String> by lazy {
        val bodies = ArrayList<String>()
        var inside = false
        for (raw in production.split('\n')) {
            val line = raw.trim()
            when {
                line.startsWith("-----BEGIN ") -> inside = true
                line.startsWith("-----END ") -> inside = false
                inside && line.isNotEmpty() -> bodies += line
            }
        }
        bodies
    }

    /** Некоторое тело ключа — реальный секрет, по которому проверяем утечку. */
    val secretSample: String by lazy { pemBodies.first() }

    // --- искажения -----------------------------------------------------------

    /** Профиль без начала: обрыв «в пути». */
    fun truncated(text: String, keepFraction: Double): String =
        text.substring(0, (text.length * keepFraction).toInt().coerceIn(0, text.length))

    /** Обрыв ровно в директиве remote — «пришло только начало». */
    fun cutAfterRemote(text: String): String {
        val marker = "\nremote 198.51.100.12 1194 udp"
        val at = text.indexOf(marker)
        return if (at < 0) text else text.substring(0, at)
    }

    /** Незакрытый блок `<key>`: BEGIN есть, END отрезан. */
    fun unterminatedKeyBlock(text: String): String =
        text.substringBefore("</key>")

    /** Незакрытый BEGIN без END вообще. */
    fun danglingBegin(text: String): String = text.replace("-----END CERTIFICATE-----", "")

    /** Закрывающий тег без открывающего. */
    fun orphanCloseTag(text: String): String = text.replaceFirst("<ca>", "")
}
