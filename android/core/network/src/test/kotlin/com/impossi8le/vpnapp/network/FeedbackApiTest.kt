package com.impossi8le.vpnapp.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Отправка пожеланий.
 *
 * Контракт простой, но два места ломаются молча: путь (`/feedback`, не
 * `/report`) и тело (`text` в JSON). Оба проверяются по записанному запросу, а
 * не по факту «ответ успешный».
 */
class FeedbackApiTest {

    private fun api(server: MockWebServer) =
        FeedbackApi(ApiClient(server.url("/api/v1").toString().trimEnd('/')))

    @Test
    fun `204 — принято, текст уходит в JSON на свой путь`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()

        val result = api(server).send("Хочу тёмную тему", appVersion = 140)

        assertEquals(FeedbackResult.Sent, result)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/feedback", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"text\":\"Хочу тёмную тему\""), "текст обязан быть в поле text")
        assertTrue(body.contains("\"platform\":\"android\""))
        assertTrue(body.contains("\"app_version\":140"))
        server.shutdown()
    }

    @Test
    fun `401 — сессия истекла, текст не считается отправленным`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()

        assertEquals(FeedbackResult.NotAuthorized, api(server).send("текст", appVersion = null))
        server.shutdown()
    }

    @Test
    fun `5xx — повтор возможен`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()

        val result = api(server).send("текст", appVersion = 140)
        assertTrue(result is FeedbackResult.Retry, "5xx не должен выглядеть доставленным")
        server.shutdown()
    }

    @Test
    fun `пустой текст не уходит в сеть`() = runTest {
        val server = MockWebServer()
        server.start()

        val result = api(server).send("   ", appVersion = 140)

        assertEquals(FeedbackResult.Retry("empty"), result)
        assertEquals(0, server.requestCount, "нечего отправлять — запроса быть не должно")
        server.shutdown()
    }

    @Test
    fun `длинный текст режется до серверного потолка`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        val long = "я".repeat(FeedbackApi.MAX_TEXT_CHARS + 100)

        api(server).send(long, appVersion = 140)

        val body = server.takeRequest().body.readUtf8()
        assertFalse(body.contains("я".repeat(FeedbackApi.MAX_TEXT_CHARS + 1)), "сверх потолка не шлём")
        assertTrue(body.contains("я".repeat(FeedbackApi.MAX_TEXT_CHARS)), "ровно потолок сохраняем")
        server.shutdown()
    }
}