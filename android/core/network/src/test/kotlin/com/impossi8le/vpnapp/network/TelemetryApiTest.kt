package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.diagnostics.EventType
import com.impossi8le.vpnapp.domain.diagnostics.TelemetryEvent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Отправка батча метрик.
 *
 * Различение ответов проверяется тестом, а не «на глаз»: `401` означает «токена
 * нет» и требует выбросить батч, `422` — «тело не принято» и повторять
 * бессмысленно, `429`/`5xx` — «повторить позже». Слить их в одно «не получилось»
 * значило бы либо терять данные, либо бесконечно долбить сервер негодным телом.
 */
class TelemetryApiTest {

    private fun event(id: String) = TelemetryEvent(
        eventId = id, type = EventType.StateChange, ts = "2026-10-13T09:20:11Z",
        state = "connected", configId = "c1", configName = "Нидерланды",
        server = "192.0.2.10:1194", durationMs = 100, code = null,
    )

    @Test
    fun `204 — принято`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        val api = TelemetryApi(ApiClient(server.url("/api/v1").toString().trimEnd('/')))

        val result = api.send(appVersion = 102, events = listOf(event("a")))

        assertEquals(TelemetryResult.Accepted, result)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/telemetry/events", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("\"event_id\":\"a\""))
        server.shutdown()
    }

    @Test
    fun `200 и 202 тоже считаются принятыми`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(202))
        server.start()
        val api = TelemetryApi(ApiClient(server.url("/api/v1").toString().trimEnd('/')))

        assertEquals(TelemetryResult.Accepted, api.send(102, listOf(event("a"))))
        assertEquals(TelemetryResult.Accepted, api.send(102, listOf(event("a"))))
        server.shutdown()
    }

    @Test
    fun `403 не теряет события — это может быть блокировка аккаунта`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(403))
        server.start()
        val api = TelemetryApi(ApiClient(server.url("/api/v1").toString().trimEnd('/')))

        // НЕ `NotAuthorized`: тот исход заставляет воркер ОЧИСТИТЬ очередь, а 403
        // контракт переиспользует и для временной блокировки аккаунта. События
        // пользователя при блокировке терять нельзя — ждём и повторяем.
        assertEquals(
            TelemetryResult.Retry,
            api.send(102, listOf(event("a"))),
            "403 не должен вести к потере накопленных событий",
        )
        server.shutdown()
    }

    @Test
    fun `401 — нет авторизации`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        val api = TelemetryApi(ApiClient(server.url("/api/v1").toString().trimEnd('/')))

        assertEquals(TelemetryResult.NotAuthorized, api.send(102, listOf(event("a"))))
        server.shutdown()
    }

    @Test
    fun `422 — тело не принято, повторять бессмысленно`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(422))
        server.start()
        val api = TelemetryApi(ApiClient(server.url("/api/v1").toString().trimEnd('/')))

        assertEquals(TelemetryResult.Rejected, api.send(102, listOf(event("a"))))
        server.shutdown()
    }

    @Test
    fun `429 и 500 — повторить позже`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        val api = TelemetryApi(ApiClient(server.url("/api/v1").toString().trimEnd('/')))

        assertEquals(TelemetryResult.Retry, api.send(102, listOf(event("a"))))
        assertEquals(TelemetryResult.Retry, api.send(102, listOf(event("a"))))
        server.shutdown()
    }

    @Test
    fun `токен сессии уходит в заголовке`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        val client = ApiClient(server.url("/api/v1").toString().trimEnd('/'))
        client.sessionToken = "session-abc"
        val api = TelemetryApi(client)

        api.send(102, listOf(event("a")))

        assertEquals("Bearer session-abc", server.takeRequest().getHeader("Authorization"))
        server.shutdown()
    }
}
