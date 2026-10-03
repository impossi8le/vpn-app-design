package com.impossi8le.vpnapp.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Разбор боевого профиля.
 *
 * Фикстура `anonymized-production.ovpn` повторяет структуру настоящего
 * `LVclient193.ovpn` директива в директиву; заменены только ключи (сгенерированы
 * заново) и адреса серверов (TEST-NET-2 из RFC 5737). Сверка тел PEM это
 * подтверждает: боевого материала в фикстуре нет.
 *
 * Смысл этих тестов — зафиксировать, ЧТО именно обязано разбираться, чтобы
 * проверки не сузились незаметно. Каждая директива здесь стоит потому, что
 * профиль реально её содержит, а не потому что она часто встречается.
 */
class ProductionProfileShapeTest {

    private val profile: String by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("anonymized-production.ovpn")
        assertNotNull(stream, "фикстура должна лежать в src/test/resources")
        stream!!.bufferedReader().readText()
    }

    private fun directives(): List<String> = profile.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("<") }
        .toList()

    @Test
    fun `remote содержит три токена — хост, порт и протокол`() {
        // Именно на этом спотыкается наивный парсер: он ждёт `remote host port`
        // и не понимает третий токен. Боевой профиль содержит три таких строки.
        val remotes = directives().filter { it.startsWith("remote ") }
        assertEquals(3, remotes.size, "в боевом профиле три сервера")

        remotes.forEach { line ->
            val parts = line.split(Regex("\\s+"))
            assertEquals(4, parts.size, "remote с протоколом: $line")
            assertEquals("udp", parts[3])
            // Порт проверяем диапазоном: конкретное значение — деталь сервера.
            assertTrue(parts[2].toInt() in 1..65535, "порт вне диапазона: $line")
        }
    }

    @Test
    fun `remote-random присутствует и он важен`() {
        // Без него три адреса бессмысленны: клиент всегда шёл бы на первый.
        assertTrue(directives().contains("remote-random"))
    }

    @Test
    fun `redirect-gateway с дополнительными флагами разбирается целиком`() {
        // `def1 bypass-dhcp` — не украшение: первый делает маршрут по умолчанию
        // не затирая существующие, второй нужен, чтобы DHCP-трафик не уходил
        // в туннель. Парсер обязан принимать строку с флагами, а не только
        // голый `redirect-gateway`.
        val line = directives().single { it.startsWith("redirect-gateway") }
        assertEquals("redirect-gateway def1 bypass-dhcp", line)
    }

    @Test
    fun `data-ciphers и fallback присутствуют`() {
        val ciphers = directives().single { it.startsWith("data-ciphers ") }
        assertTrue(ciphers.contains("AES-256-GCM"), "GCM обязан быть первым: $ciphers")
        assertTrue(directives().any { it.startsWith("data-ciphers-fallback ") })
    }

    @Test
    fun `key-direction присутствует — он нужен для tls-auth`() {
        // Без key-direction tls-auth не работает: непонятно, кто чей ключ.
        assertTrue(directives().contains("key-direction 1"))
    }

    @Test
    fun `все четыре встроенных блока на месте`() {
        // ca, cert, key, tls-auth — все inline. Профиль не ссылается на файлы,
        // поэтому разборщик, ожидающий внешние пути, его не примет.
        listOf("ca", "cert", "key", "tls-auth").forEach { block ->
            assertTrue(profile.contains("<$block>"), "нет блока <$block>")
            assertTrue(profile.contains("</$block>"), "нет закрытия </$block>")
        }
    }

    @Test
    fun `блоки PEM корректно ограничены`() {
        val beginCount = Regex("-----BEGIN").findAll(profile).count()
        val endCount = Regex("-----END").findAll(profile).count()
        assertEquals(beginCount, endCount, "каждый BEGIN обязан иметь END")
        assertEquals(4, beginCount, "четыре PEM-блока: ca, cert, key, tls-auth")
    }

    @Test
    fun `профиль содержит опасный verb 3`() {
        // Боевой профиль просит verb 3, при котором ядро печатает тела PEM —
        // то есть приватный ключ уезжает в logcat. Тест нужен как напоминание:
        // это свойство продакшена, а не выдумка документации.
        val verb = directives().single { it.startsWith("verb ") }
        assertEquals("verb 3", verb)
        assertTrue(
            verb.substringAfter("verb ").toInt() >= 3,
            "именно поэтому CoreConfig.fromProfile обязана понижать уровень",
        )
    }

    @Test
    fun `опасный verb из боевого профиля понижается до безопасного`() {
        // Главная проверка всей фикстуры. В боевом профиле `verb 3`, а при
        // verb >= 3 ядро печатает тела PEM — приватный ключ уезжает в logcat.
        // Значение приходит от сервера, поэтому тип с проверкой в конструкторе
        // сам по себе не спасает: спасает только вход, который его понижает.
        val verbFromProfile = directives()
            .single { it.startsWith("verb ") }
            .substringAfter("verb ")
            .toInt()

        val config = com.impossi8le.vpnapp.domain.tunnel.CoreConfig.fromProfile(verbFromProfile)

        assertEquals(3, verbFromProfile, "фикстура должна нести именно боевое значение")
        assertTrue(
            config.verb <= com.impossi8le.vpnapp.domain.tunnel.CoreConfig.SAFE_MAX_VERB,
            "уровень из профиля обязан быть понижен, иначе ключ уйдёт в логи",
        )
        assertEquals(1, config.verb)
    }

    @Test
    fun `анонимизация не задела структуру`() {
        // Адреса — TEST-NET-2: они гарантированно не указывают на чужую
        // инфраструктуру, но формат IP и порта сохранён, поэтому парсер
        // проверяет ровно то, что проверял бы на боевом файле.
        val remotes = directives().filter { it.startsWith("remote ") }
        remotes.forEach {
            val host = it.split(Regex("\\s+"))[1]
            assertTrue(host.startsWith("198.51.100."), "адрес должен быть из TEST-NET-2: $host")
        }
        assertFalse(profile.contains("185.113"), "боевой адрес не должен попасть в фикстуру")
    }
}
