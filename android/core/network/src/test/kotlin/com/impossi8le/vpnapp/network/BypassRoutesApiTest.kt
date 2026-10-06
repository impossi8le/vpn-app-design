package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.tunnel.BypassRoute
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BypassRoutesApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BypassRoutesApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = BypassRoutesApi(ApiClient(baseUrl = server.url("/api/v1").toString().trimEnd('/')))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `список разбирается в подсети`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"updated_at":"2026-10-06T00:00:00Z","routes":["87.240.129.0/24","155.212.204.0/24"]}""",
            ),
        )

        assertEquals(
            listOf(BypassRoute("87.240.129.0", 24), BypassRoute("155.212.204.0", 24)),
            api.routes(),
        )
        assertEquals("/api/v1/app/bypass-routes?platform=android", server.takeRequest().path)
    }

    @Test
    fun `мусорные строки пропускаются, хорошие остаются`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"routes":["87.240.129.0/24","не-адрес","10.0.0.0/99"]}""",
            ),
        )

        assertEquals(listOf(BypassRoute("87.240.129.0", 24)), api.routes())
    }

    @Test
    fun `нет списка — обходов нет, а не падение`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody(""))
        assertTrue(api.routes().isEmpty())

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(api.routes().isEmpty())

        server.enqueue(MockResponse().setResponseCode(200).setBody("не json"))
        assertTrue(api.routes().isEmpty())
    }
}
