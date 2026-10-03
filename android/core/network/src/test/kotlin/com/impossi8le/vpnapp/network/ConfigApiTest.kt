package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.config.ConfigFetchError
import com.impossi8le.vpnapp.domain.config.ConfigFetchException
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConfigApiTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient
    private lateinit var api: ConfigApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ApiClient(baseUrl = server.url("/api/v1").toString().trimEnd('/'))
        api = ConfigApi(client)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `список подключений разбирается со статусом у каждого`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"chat_id":123,"configs":[
                  {"id":"nl-ams-1","name":"Нидерланды · Амстердам",
                   "location":{"country_code":"NL","city":"Амстердам"},
                   "start_date":"2026-09-01T00:00:00Z","end_date":"2026-12-01T00:00:00Z",
                   "status":"active"},
                  {"id":"nl-rtm-1","name":"Нидерланды · Роттердам",
                   "location":{"country_code":"NL","city":"Роттердам"},
                   "start_date":"2026-05-01T00:00:00Z","end_date":"2026-09-01T00:00:00Z",
                   "status":"expired"}
                ]}
                """.trimIndent(),
            ),
        )

        val list = api.listConfigs().getOrThrow()
        assertEquals(2, list.configs.size)
        // У каждого подключения СВОЙ статус: один истёк — остальные работают,
        // экран не блокируется (контракт §3).
        assertEquals(SubscriptionStatus.ACTIVE, list.configs[0].status)
        assertEquals(SubscriptionStatus.EXPIRED, list.configs[1].status)
        assertEquals("Амстердам", list.configs[0].city)
    }

    @Test
    fun `пустой список — валидный ответ, а не ошибка`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"chat_id":1,"configs":[]}"""))

        val list = api.listConfigs().getOrThrow()
        assertTrue(list.configs.isEmpty(), "состояние «подписок нет» — не ошибка")
    }

    @Test
    fun `неизвестный статус не считается рабочим`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"chat_id":1,"configs":[{"id":"x","name":"x","location":{},"start_date":"2026-01-01T00:00:00Z","end_date":"2026-02-01T00:00:00Z","status":"weird"}]}""",
            ),
        )

        val list = api.listConfigs().getOrThrow()
        assertEquals(
            SubscriptionStatus.EXPIRED,
            list.configs[0].status,
            "непонятный статус нельзя показывать как рабочий",
        )
    }

    @Test
    fun `config отдаёт сырые байты с версией и хешем`() = runTest {
        val ovpn = "client\ndev tun\nremote host 1194\n"
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/x-openvpn-profile")
                .setHeader("X-Config-Version", "2026-10-02T09:00:00Z")
                .setHeader("X-Config-Hash", "sha256:ab12")
                .setBody(ovpn),
        )

        val fetched = api.fetchConfig("nl-ams-1").getOrThrow()
        assertEquals(ovpn, String(fetched.raw), "тело — сырой .ovpn, не JSON")
        assertEquals("2026-10-02T09:00:00Z", fetched.version)
        assertEquals("sha256:ab12", fetched.hash)
        assertTrue(server.takeRequest().path!!.endsWith("/config/nl-ams-1"))
    }

    @Test
    fun `токен сессии уходит в заголовке авторизации`() = runTest {
        client.sessionToken = "tok-123"
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"chat_id":1,"configs":[]}"""))

        api.listConfigs().getOrThrow()
        assertEquals("Bearer tok-123", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `без токена заголовок авторизации не отправляется`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"chat_id":1,"configs":[]}"""))

        api.listConfigs().getOrThrow()
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `отозванный конфиг отличается от истёкшей подписки`() = runTest {
        // Оба приходят с 403 — различать надо по коду, иначе UI покажет не то.
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"error":{"code":"config_revoked","message":"отозван"}}"""),
        )
        assertEquals(
            ConfigFetchError.ConfigRevoked,
            (api.fetchConfig("x").exceptionOrNull() as ConfigFetchException).error,
        )

        server.enqueue(
            MockResponse().setResponseCode(403).setBody("""{"error":{"code":"subscription_expired","message":"истекла"}}"""),
        )
        assertEquals(
            ConfigFetchError.SubscriptionExpired,
            (api.fetchConfig("x").exceptionOrNull() as ConfigFetchException).error,
        )
    }

    @Test
    fun `401 на любом запросе даёт Unauthorized`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"error":{"code":"unauthorized","message":"нет сессии"}}"""),
        )
        assertEquals(
            ApiError.Unauthorized,
            (api.listConfigs().exceptionOrNull() as ApiException).error,
        )
    }

    @Test
    fun `битое тело не роняет парсер`() = runTest {
        // Опечатка на бэкенде должна давать понятную ошибку, а не падение.
        server.enqueue(MockResponse().setResponseCode(200).setBody("не json вовсе"))

        val result = api.listConfigs()
        assertTrue(result.isFailure)
        assertTrue(
            (result.exceptionOrNull() as ApiException).error is ApiError.Network,
            "неразобранное тело — это сбой обращения, а не данные",
        )
    }
}
