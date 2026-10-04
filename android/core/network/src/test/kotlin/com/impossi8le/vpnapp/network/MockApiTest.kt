package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.auth.PollOutcome
import com.impossi8le.vpnapp.domain.config.ConfigFetchError
import com.impossi8le.vpnapp.domain.config.ConfigFetchException
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionEvidence
import com.impossi8le.vpnapp.domain.protection.ProtectionProbe
import com.impossi8le.vpnapp.domain.protection.ProtectionVerdict
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * MockApi — подставной бэкенд демо-режима. Тесты фиксируют не только сценарий
 * входа, но и границы подставного API: он имитирует сеть и авторизацию и НЕ
 * имеет права ничего сообщать о защите (§6). Если эта граница когда-нибудь
 * размоется, приложение начнёт показывать зелёное без замера — тесты обязаны
 * это поймать.
 *
 * `latency = 0`: задержка нужна UI, тестам — нет, иначе каждый кейс ждал бы
 * 400 мс впустую.
 */
class MockApiTest {

    private fun api() = MockApi(latency = 0)

    // --- Авторизация -------------------------------------------------------

    @Test
    fun `startLogin выдаёт код, ссылку и непустой секрет`() = runTest {
        val challenge = api().startLogin("Pixel").getOrThrow()

        assertTrue(challenge.publicCode.isNotBlank(), "код входа не может быть пустым")
        assertTrue(challenge.secret.isNotBlank(), "без секрета нечем доказать, что вход начали мы")
        assertTrue(
            challenge.deepLink.contains(challenge.publicCode),
            "ссылка обязана нести именно выданный код — иначе пользователь откроет чужую операцию",
        )
        assertTrue(challenge.expiresAtEpochSeconds > 0)
    }

    @Test
    fun `два запуска входа дают разные секреты`() = runTest {
        // Секрет — единственное, что не видно в ссылке. Если он повторяется,
        // знание чужого кода из чата даёт доступ к следующей сессии.
        val first = api().startLogin("Pixel").getOrThrow()
        val second = api().startLogin("Pixel").getOrThrow()
        assertNotEquals(first.secret, second.secret, "секрет обязан быть случайным на каждый вход")
    }

    @Test
    fun `pollSession с чужим publicCode даёт Expired, а не Confirmed`() = runTest {
        // Защита от подмены операции: злоумышленник подсовывает СВОЙ код, надеясь
        // получить сессию по чужому секрету. Ответ обязан быть терминальным Expired.
        val mock = api()
        mock.startLogin("Pixel").getOrThrow() // выдаём настоящий код

        val outcome = mock.pollSession(
            publicCode = "deadbeef0000",
            secret = "любой",
            deviceNonce = "4821",
        ).getOrThrow()

        assertEquals(PollOutcome.Expired, outcome)
        assertFalse(outcome is PollOutcome.Confirmed, "чужой код НЕ должен подтверждать сессию")
    }

    @Test
    fun `pollSession с пустым или нецифровым nonce остаётся Pending`() = runTest {
        // nonce замыкает подтверждение на конкретное устройство (login-CSRF).
        // Пустой или буквенный ввод — это «пользователь ещё не ввёл», и это
        // НЕ повод выдать сессию.
        val mock = api()
        val code = mock.startLogin("Pixel").getOrThrow().publicCode

        for (bad in listOf("", "   ", "ab12", "12 34", "0x12")) {
            val outcome = mock.pollSession(code, "s", bad).getOrThrow()
            assertTrue(
                outcome is PollOutcome.Pending,
                "nonce='$bad' должен оставлять опрос в Pending, а не подтверждать",
            )
        }
    }

    @Test
    fun `pollSession с правильным кодом и четырьмя цифрами подтверждает сессию`() = runTest {
        val mock = api()
        val code = mock.startLogin("Pixel").getOrThrow().publicCode

        val outcome = mock.pollSession(code, "secret", "4821").getOrThrow()

        assertTrue(outcome is PollOutcome.Confirmed, "корректный вход обязан подтвердиться")
        val session = (outcome as PollOutcome.Confirmed).session
        assertTrue(session.token.isNotBlank(), "подтверждённая сессия не может быть пустой")
        assertTrue(session.expiresAtEpochSeconds > 0)
    }

    // --- Список подключений ------------------------------------------------

    @Test
    fun `listConfigs содержит и рабочие, и истёкшее подключение`() = runTest {
        // Экран обязан показывать истёкшее отдельно и не блокироваться. Список
        // только из рабочих эту логику бы не проверял.
        val list = api().listConfigs().getOrThrow()

        assertTrue(
            list.configs.any { it.status == SubscriptionStatus.ACTIVE },
            "нужно хотя бы одно рабочее подключение",
        )
        val expired = list.configs.filter { it.status == SubscriptionStatus.EXPIRED }
        assertEquals(1, expired.size, "в списке ожидается ровно одно истёкшее подключение")
        assertTrue(
            expired.single().endDateEpochSeconds < System.currentTimeMillis() / 1000,
            "EXPIRED должен быть честно истёкшим: дата окончания в прошлом",
        )
    }

    // --- Получение конфига -------------------------------------------------

    @Test
    fun `fetchConfig по неизвестному id даёт NotFound`() = runTest {
        val result = api().fetchConfig("no-such-id")

        assertTrue(result.isFailure, "конфига с таким id нет — это ошибка")
        assertEquals(
            ConfigFetchError.NotFound,
            (result.exceptionOrNull() as ConfigFetchException).error,
        )
    }

    @Test
    fun `fetchConfig по истёкшему подключению даёт SubscriptionExpired`() = runTest {
        // Сервер не отдаёт конфиг по неактивной подписке. Если бы подставной API
        // отдавал — приложение училось бы работать с тем, чего в реальности нет.
        val mock = api()
        mock.profileBytes = "client\ndev tun\n".toByteArray()

        val result = mock.fetchConfig("nl-rtm-1") // истёкшее из mockConfigs

        assertTrue(result.isFailure, "по истёкшей подписке конфиг выдаваться не должен")
        assertEquals(
            ConfigFetchError.SubscriptionExpired,
            (result.exceptionOrNull() as ConfigFetchException).error,
        )
    }

    @Test
    fun `fetchConfig без profileBytes даёт NetworkUnavailable, а не пустой конфиг`() = runTest {
        // Пустой конфиг был бы опаснее ошибки: ядро приняло бы пустышку за
        // рабочий профиль и затёрло установленный. Ошибка честнее.
        val mock = api()
        mock.profileBytes = null

        val result = mock.fetchConfig("nl-ams-1")

        assertTrue(result.isFailure)
        assertEquals(
            ConfigFetchError.NetworkUnavailable,
            (result.exceptionOrNull() as ConfigFetchException).error,
        )
    }

    @Test
    fun `fetchConfig с profileBytes отдаёт версию и хеш sha256`() = runTest {
        val body = "client\ndev tun\nremote host 1194\n".toByteArray()
        val mock = api()
        mock.profileBytes = body

        val fetched = mock.fetchConfig("nl-ams-1").getOrThrow()

        assertTrue(fetched.raw.contentEquals(body), "тело — ровно те байты, что положили")
        assertTrue(fetched.version.isNotBlank(), "версия нужна, чтобы не перезаписывать то же самое")
        assertTrue(fetched.hash.startsWith("sha256:"), "хеш обязан быть помечен алгоритмом")
        assertTrue(fetched.hash.length > "sha256:".length, "за префиксом должен быть сам дайджест")
        assertEquals(
            "sha256:" + Crypto.sha256Hex(body),
            fetched.hash,
            "хеш обязан считаться от байтов профиля — иначе сверку версии не провести",
        )
    }

    // --- Граница: MockApi не имеет права выдавать зелёный -------------------

    @Test
    fun `MockApi не является ProtectionProbe`() {
        // Смысл подставного API — имитировать сеть и авторизацию. Статус защиты
        // определяется ИСКЛЮЧИТЕЛЬНО замером на устройстве (§6). Если бы MockApi
        // умел отвечать на `verify()`, демо-режим мог бы показать зелёное без
        // замера — и весь инвариант обесценился бы.
        assertFalse(
            ProtectionProbe::class.java.isAssignableFrom(MockApi::class.java),
            "подставной API не должен реализовывать пробу защиты",
        )
    }

    @Test
    fun `среди публичных методов MockApi нет возвращающих ProtectionVerdict или ConnectionStatus`() {
        // Проверяем в типе: ни один публичный метод не отдаёт вердикт о защите и
        // не конструирует состояние соединения. Это тот же инвариант, но на случай,
        // если зелёное попытаются протащить не через интерфейс, а отдельным методом.
        val forbiddenReturns = setOf(
            ProtectionVerdict::class.java,
            ConnectionStatus::class.java,
            ProtectionEvidence::class.java,
        )
        val forbiddenParams = forbiddenReturns

        val offenders = MockApi::class.java.methods.filter { method ->
            method.returnType in forbiddenReturns ||
                method.parameterTypes.any { it in forbiddenParams }
        }.map { it.name }

        assertTrue(
            offenders.isEmpty(),
            "MockApi не должен трогать типы защиты, но обнаружены методы: $offenders",
        )
    }

    @Test
    fun `MockApi реализует ровно сервисы сети и авторизации`() {
        // Явный список интерфейсов: добавили сюда защиту — тест упал, и это
        // заставит задуматься, а не протащить зелёное «между делом».
        val interfaces = MockApi::class.java.interfaces.map { it.simpleName }.toSet()
        assertEquals(setOf("AuthService", "ConfigService"), interfaces)
    }
}
