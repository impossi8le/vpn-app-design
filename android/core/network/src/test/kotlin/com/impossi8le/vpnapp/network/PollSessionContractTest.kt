package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.auth.PollOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Контракт ответа `/auth/poll` в подтверждённом состоянии.
 *
 * Эти тесты закрывают расхождение, найденное при сверке контракта с кодом:
 * контракт не описывал `expires_at` у `/auth/poll`, а клиент его требовал.
 * Хуже того — отсутствие поля раньше давало сессию с нулевым сроком, и вход
 * выглядел сломанным без внятной причины.
 *
 * Тесты фиксируют новое поведение: **отсутствие обязательного поля — ошибка**,
 * а не молчаливый ноль. Это важно именно для сроков: ноль выглядит
 * правдоподобно («истекла давно») и потому маскирует причину.
 */
class PollSessionContractTest {

    private lateinit var server: MockWebServer
    private lateinit var api: AuthApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = AuthApi(
            ApiClient(baseUrl = server.url("/").toString().trimEnd('/'), http = OkHttpClient()),
            Json { ignoreUnknownKeys = true },
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(code: Int, body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setBody(body),
        )
    }

    @Test
    fun `подтверждённый вход возвращает токен и срок`() = runBlocking {
        enqueue(
            200,
            """
            {
              "status": "confirmed",
              "session_token": "eyJhbGciOi",
              "expires_at": "2026-11-02T10:00:00Z",
              "chat_id": 123456789
            }
            """.trimIndent(),
        )

        val outcome = api.pollSession("code", "secret", "4821").getOrThrow()

        val confirmed = outcome as PollOutcome.Confirmed
        assertEquals("eyJhbGciOi", confirmed.session.token)
        // Срок разобран из ISO-8601, а не оставлен нулём.
        assertTrue(
            confirmed.session.expiresAtEpochSeconds > 0,
            "срок сессии обязан быть разобран: с нулём клиент выйдет из неё сразу",
        )
        assertEquals(123456789L, confirmed.chatId)
    }

    @Test
    fun `отсутствие expires_at это ошибка, а не нулевой срок`() = runBlocking {
        // Ровно тот случай, который раньше записывал сессию со сроком 1970 года.
        enqueue(
            200,
            """
            {
              "status": "confirmed",
              "session_token": "eyJhbGciOi",
              "chat_id": 123456789
            }
            """.trimIndent(),
        )

        val result = api.pollSession("code", "secret", "4821")

        assertTrue(
            result.isFailure,
            "без срока сессии вход считать состоявшимся нельзя: это скрытая поломка",
        )
        assertTrue(
            result.exceptionOrNull() is ApiException,
            "ошибка должна быть типизированной, чтобы экран показал понятный текст",
        )
    }

    @Test
    fun `отсутствие токена это ошибка`() = runBlocking {
        // Токен без значения означал бы «вход состоялся» без права что-либо делать.
        enqueue(
            200,
            """
            {
              "status": "confirmed",
              "expires_at": "2026-11-02T10:00:00Z",
              "chat_id": 123456789
            }
            """.trimIndent(),
        )

        val result = api.pollSession("code", "secret", "4821")

        assertTrue(result.isFailure, "без токена сессия бессмысленна")
    }

    @Test
    fun `ожидание возвращает retry_after_ms а не ошибку`() = runBlocking {
        enqueue(200, """{"status":"pending","retry_after_ms":2000}""")

        val outcome = api.pollSession("code", "secret", "4821").getOrThrow()

        val pending = outcome as PollOutcome.Pending
        assertEquals(2000L, pending.retryAfterMillis)
    }

    @Test
    fun `already_consumed это не тупик, а повод войти заново`() = runBlocking {
        // Контракт требует именно такого поведения: потерянный ответ не должен
        // оставлять пользователя на экране ожидания навсегда.
        enqueue(
            409,
            """{"error":{"code":"already_consumed","message":"used","retryable":false}}""",
        )

        val outcome = api.pollSession("code", "secret", "4821").getOrThrow()

        assertEquals(PollOutcome.AlreadyConsumed, outcome)
    }

    @Test
    fun `несовпадение кода отличается от неверного секрета`() = runBlocking {
        // Пользователю это разные сообщения: код можно ввести заново, а
        // испорченный секрет требует начать вход сначала.
        enqueue(403, """{"error":{"code":"nonce_mismatch","message":"wrong","retryable":true}}""")
        val mismatch = api.pollSession("code", "secret", "0000").exceptionOrNull() as ApiException
        assertEquals(ApiError.NonceMismatch, mismatch.error)

        enqueue(401, """{"error":{"code":"invalid_secret","message":"bad","retryable":false}}""")
        val secret = api.pollSession("code", "secret", "4821").exceptionOrNull() as ApiException
        assertEquals(ApiError.InvalidSecret, secret.error)
    }

    @Test
    fun `голый 402 без кода не должен выглядеть как непонятный сбой`() = runBlocking {
        // Найденное расхождение: контракт разрешает `402/403 subscription_expired`,
        // но клиент различает ситуации по `code`. Голый `402` без тела он трактует
        // как неожиданный ответ — и это ЗАФИКСИРОВАННОЕ поведение: сервер обязан
        // присылать `code`, иначе пользователь увидит «ошибку» вместо
        // «подписка истекла».
        enqueue(402, "")

        val error = ErrorMapping.fromStatus(402, "")

        assertEquals(
            ApiError.Unexpected(402, ""),
            error,
            "голый 402 без code не опознаётся: контракт требует поле code",
        )
    }
}