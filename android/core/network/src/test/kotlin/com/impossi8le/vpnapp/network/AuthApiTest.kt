package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.auth.PollOutcome
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Проверяется против MockWebServer, а не против мока интерфейса.
 *
 * Мок интерфейса вернул бы уже разобранный объект и спрятал самое опасное —
 * разбор тела и маппинг статусов. Именно там живут ошибки, которые на живом
 * сервере выглядят как «приложение сломалось».
 */
class AuthApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AuthApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = AuthApi(ApiClient(baseUrl = server.url("/api/v1").toString().trimEnd('/')))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `startLogin не отправляет секрет в открытом виде`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(201).setBody(
                """{"public_code":"a7f3c9d2e1b4","deep_link":"https://t.me/bot?start=login_a7f3c9d2e1b4","expires_at":"2026-10-02T10:15:00Z"}""",
            ),
        )

        val challenge = api.startLogin("Pixel").getOrThrow()
        val request = server.takeRequest()
        val body = request.body.readUtf8()

        assertTrue(request.path!!.endsWith("/auth/link"))
        assertTrue(body.contains("secret_hash"), "должен уходить только хеш")
        assertTrue(body.contains("sha256:"))
        assertTrue(body.contains("\"platform\":\"android\""), "платформа обязана быть android")
        assertTrue(
            !body.contains(challenge.secret),
            "секрет НИКОГДА не покидает устройство — тело запроса не должно его содержать",
        )
        assertEquals("a7f3c9d2e1b4", challenge.publicCode)
    }

    @Test
    fun `poll confirmed отдаёт сессию`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"confirmed","session_token":"tok","expires_at":"2026-11-02T10:00:00Z","chat_id":42}""",
            ),
        )

        val outcome = api.pollSession("a7f3c9d2e1b4", "secret", "4821").getOrThrow()
        assertTrue(outcome is PollOutcome.Confirmed)
        assertEquals("tok", (outcome as PollOutcome.Confirmed).session.token)
        assertEquals(42L, outcome.chatId)
    }

    @Test
    fun `pending — штатный ответ, а не ошибка`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"status":"pending","retry_after_ms":2000}"""),
        )

        val outcome = api.pollSession("a7f3c9d2e1b4", "secret", "4821").getOrThrow()
        assertEquals(PollOutcome.Pending(2000L), outcome)
    }

    @Test
    fun `already_consumed — не тупик, а отдельный исход`() = runTest {
        // Потерянный ответ: клиент не получил confirmed, повтор вернул 409.
        // Приложение обязано предложить вход заново, а не зависнуть навсегда.
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"error":{"code":"already_consumed","message":"сессия уже выдана","retryable":false}}""",
            ),
        )

        val outcome = api.pollSession("a7f3c9d2e1b4", "secret", "4821").getOrThrow()
        assertEquals(PollOutcome.AlreadyConsumed, outcome)
    }

    @Test
    fun `неверный nonce отличается от неверного секрета`() = runTest {
        // Оба кода приходят с 403, и по статусу их не различить. Различие важно
        // для UI: при nonce_mismatch операция жива и код можно ввести заново,
        // при invalid_secret нужно начинать вход заново.
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"error":{"code":"nonce_mismatch","message":"код не совпал","retryable":false}}""",
            ),
        )
        val nonceError = (api.pollSession("c", "s", "0000").exceptionOrNull() as ApiException).error
        assertEquals(ApiError.NonceMismatch, nonceError)

        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"error":{"code":"invalid_secret","message":"секрет не совпал","retryable":false}}""",
            ),
        )
        val secretError = (api.pollSession("c", "s", "0000").exceptionOrNull() as ApiException).error
        assertEquals(ApiError.InvalidSecret, secretError)
    }

    @Test
    fun `expired и denied распознаются как терминальные состояния`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"expired"}"""))
        assertEquals(PollOutcome.Expired, api.pollSession("c", "s", "n").getOrThrow())

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"denied"}"""))
        assertEquals(PollOutcome.Denied, api.pollSession("c", "s", "n").getOrThrow())
    }

    @Test
    fun `rate limit маппится в типизированную ошибку`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(429).setBody(
                """{"error":{"code":"rate_limited","message":"слишком часто","retryable":true}}""",
            ),
        )

        val error = (api.pollSession("c", "s", "n").exceptionOrNull() as ApiException).error
        assertEquals(ApiError.RateLimited, error)
    }

    @Test
    fun `сетевой сбой даёт Network, а не падение`() = runTest {
        server.shutdown() // порт закрыт — соединение не состоится

        val result = api.startLogin("Pixel")
        assertTrue(result.isFailure)
        assertTrue(
            (result.exceptionOrNull() as ApiException).error is ApiError.Network,
            "недоступность сети — это типизированная ошибка, а не сырое исключение",
        )
    }
}
