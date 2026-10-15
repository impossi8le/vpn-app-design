package com.impossi8le.vpnapp.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Отправка полного отчёта.
 *
 * `report_id` читается из ответа не для красоты: по нему поддержка находит отчёт,
 * и клиент показывает его пользователю. Но отсутствие поля не должно превращать
 * успешную отправку в неудачу — сервер вправе ответить `204`.
 */
class ReportApiTest {

    @Test
    fun `201 с report_id — отправлено`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"report_id":"r-42"}"""))
        server.start()
        val api = ReportApi(ApiClient(server.url("/api/v1").toString().trimEnd('/')))

        val result = api.send(report = "текст отчёта", logs = "лог", config = null, meta = "{}")

        assertEquals(ReportResult.Sent("r-42"), result)
        val recorded = server.takeRequest()
        assertEquals("/api/v1/report", recorded.path)
        assertTrue(recorded.body.readUtf8().contains("текст отчёта"))
        server.shutdown()
    }

    @Test
    fun `204 без тела — тоже отправлено`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        val api = ReportApi(ApiClient(server.url("/api/v1").toString().trimEnd('/')))

        val result = api.send("отчёт", null, null, "{}")

        assertTrue(result is ReportResult.Sent)
        assertEquals(null, (result as ReportResult.Sent).reportId, "тела нет — id неизвестен, но это успех")
        server.shutdown()
    }

    @Test
    fun `401 — нужен вход`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        val api = ReportApi(ApiClient(server.url("/api/v1").toString().trimEnd('/')))

        assertEquals(ReportResult.NotAuthorized, api.send("отчёт", null, null, "{}"))
        server.shutdown()
    }

    @Test
    fun `413 — отчёт слишком большой`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(413))
        server.start()
        val api = ReportApi(ApiClient(server.url("/api/v1").toString().trimEnd('/')))

        assertEquals(ReportResult.TooLarge, api.send("отчёт", "лог", null, "{}"))
        server.shutdown()
    }

    @Test
    fun `429 — повторить позже, отчёт не теряется`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429))
        server.start()
        val api = ReportApi(ApiClient(server.url("/api/v1").toString().trimEnd('/')))

        val result = api.send("отчёт", "лог", null, "{}")

        assertTrue(result is ReportResult.Retry, "429 обязан вести к повтоpy, а не к отказу")
        assertEquals("rate_limited", (result as ReportResult.Retry).reason)
        server.shutdown()
    }

    @Test
    fun `незнакомый 4xx — отчёт остаётся в очереди`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400))
        server.start()
        val api = ReportApi(ApiClient(server.url("/api/v1").toString().trimEnd('/')))

        val result = api.send("отчёт", null, null, "{}")

        assertTrue(result is ReportResult.Retry, "незнакомый 4xx не должен молча терять отчёт")
        server.shutdown()
    }

    @Test
    fun `сеть недоступна — повторить позже`() = runTest {
        val api = ReportApi(ApiClient("http://127.0.0.1:1/"))

        val result = api.send("отчёт", null, null, "{}")

        assertNotNull((result as? ReportResult.Retry)?.reason)
    }
}
