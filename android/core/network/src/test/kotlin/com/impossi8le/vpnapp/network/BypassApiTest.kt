package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.tunnel.BypassCatalog
import com.impossi8le.vpnapp.domain.tunnel.BypassResolve
import com.impossi8le.vpnapp.domain.tunnel.BypassRoute
import com.impossi8le.vpnapp.domain.tunnel.BypassService
import com.impossi8le.vpnapp.domain.tunnel.BypassWrite
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Управление обходами против подставного сервера.
 *
 * Главное, что здесь проверяется, — различение исходов, которого требует §6:
 * `400` на `resolve` («адрес негоден») — это НЕ сбой сети, а неудача чтения —
 * это НЕ пустой список. Смешать их значило бы показывать пользователю неверный
 * текст и врать о состоянии обходов.
 */
class BypassApiTest {

    private lateinit var server: MockWebServer
    private lateinit var client: ApiClient
    private lateinit var api: BypassApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ApiClient(baseUrl = server.url("/api/v1").toString().trimEnd('/'))
        api = BypassApi(client)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // --- Каталог сервисов ---

    @Test
    fun `каталог разбирается в сервисы с доменами`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"services":[{"key":"vk","title":"ВКонтакте","domains":["vk.com","vk.ru"]}]}""",
            ),
        )

        val catalog = api.catalog()
        assertEquals(
            BypassCatalog.Loaded(
                listOf(BypassService("vk", "ВКонтакте", listOf("vk.com", "vk.ru"))),
            ),
            catalog,
        )
        assertEquals("/api/v1/app/bypass-services?platform=android", server.takeRequest().path)
    }

    @Test
    fun `сбой каталога это Failed, а не пустой каталог`() = runTest {
        // Пустой Loaded значил бы «сервисов нет», а это другое утверждение.
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        assertEquals(BypassCatalog.Failed, api.catalog())

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(BypassCatalog.Failed, api.catalog())

        server.enqueue(MockResponse().setResponseCode(200).setBody("не json"))
        assertEquals(BypassCatalog.Failed, api.catalog())
    }

    // --- Разбор адреса ---

    @Test
    fun `resolve отдаёт подсети и шлёт цели`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"routes":["87.240.129.0/24"]}"""),
        )

        assertEquals(
            BypassResolve.Resolved(listOf(BypassRoute("87.240.129.0", 24))),
            api.resolve(listOf("vk.com")),
        )

        val request = server.takeRequest()
        assertEquals("/api/v1/app/bypass/resolve", request.path)
        assertTrue(request.body.readUtf8().contains("\"targets\""))
    }

    @Test
    fun `400 на resolve это негодная цель, а не сбой сети`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"code":"invalid_target"}}"""),
        )
        assertEquals(BypassResolve.InvalidTarget, api.resolve(listOf("0.0.0.0/0")))
    }

    @Test
    fun `сбой сети на resolve это Failed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody(""))
        assertEquals(BypassResolve.Failed, api.resolve(listOf("vk.com")))
    }

    // --- Запись списка ---

    @Test
    fun `set шлёт подсети в формате CIDR и читает count`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"count":2}"""))

        assertEquals(
            BypassWrite.Applied(2),
            api.set(listOf(BypassRoute("87.240.129.0", 24), BypassRoute("77.88.0.0", 16))),
        )

        val request = server.takeRequest()
        assertEquals("/api/v1/app/bypass/set", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("87.240.129.0/24"), "подсеть уходит строкой CIDR: $body")
        assertTrue(body.contains("77.88.0.0/16"), "подсеть уходит строкой CIDR: $body")
    }

    @Test
    fun `set шлёт подпись рядом с CIDR, но не выдумывает пустую`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"count":2}"""))
        api.set(
            listOf(
                BypassRoute("87.240.129.0", 24, name = "ВКонтакте"),
                BypassRoute("77.88.0.0", 16),
            ),
        )

        val body = server.takeRequest().body.readUtf8()
        // Подпись и адрес — РАЗНЫЕ поля: сервер пишет подпись `#`-строкой НАД
        // `route … net_gateway`, и склейка сломала бы обе строки файла.
        assertTrue(body.contains("\"label\":\"ВКонтакте\""), "подпись обязана уехать: $body")
        assertTrue(body.contains("\"cidr\":\"87.240.129.0/24\""), "адрес отдельным полем: $body")
        assertEquals(1, Regex("\"label\"").findAll(body).count(), "у записи без имени подписи нет: $body")
    }

    @Test
    fun `400 на set это Invalid, а не частичная запись`() = runTest {
        // Сервер отвергает весь запрос из-за одной записи — исхода «частично» нет.
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":"invalid_target"}}"""))
        assertEquals(BypassWrite.Invalid, api.set(listOf(BypassRoute("87.240.129.0", 24))))
    }

    @Test
    fun `сбой сети на set это Failed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        assertEquals(BypassWrite.Failed, api.set(listOf(BypassRoute("87.240.129.0", 24))))
    }

    // --- Чтение текущего списка ---

    @Test
    fun `текущие обходы читаются`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"routes":["87.240.129.0/24","не-адрес"]}"""),
        )
        assertEquals(listOf(BypassRoute("87.240.129.0", 24)), api.fetchRoutes())
    }

    @Test
    fun `подписи из entries доходят до списка`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"entries":[{"cidr":"87.240.129.0/24","label":"ВКонтакте"},""" +
                    """{"cidr":"155.212.204.0/24"}]}""",
            ),
        )

        assertEquals(
            listOf(
                BypassRoute("87.240.129.0", 24, name = "ВКонтакте"),
                BypassRoute("155.212.204.0", 24),
            ),
            api.fetchRoutes(),
        )
    }

    @Test
    fun `старый ответ без entries читается из routes`() = runTest {
        // Сервер, ещё не знающий о подписях, отдаёт плоский `routes`: клиент
        // обязан работать и с ним, иначе обновление клиента ломало бы старый сервер.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"routes":["87.240.129.0/24"]}"""),
        )
        assertEquals(listOf(BypassRoute("87.240.129.0", 24)), api.fetchRoutes())
    }

    @Test
    fun `сбой чтения это null, а не пустой список`() = runTest {
        // Пустой список значил бы «обходов нет»; null — «прочитать не удалось».
        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        assertNull(api.fetchRoutes())
    }

    // --- Токен сессии в авторизованных вызовах ---

    @Test
    fun `без токена resolve и set идут без заголовка авторизации`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"routes":[]}"""))
        api.resolve(listOf("vk.com"))
        assertNull(server.takeRequest().getHeader("Authorization"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"count":0}"""))
        api.set(emptyList())
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `с токеном resolve и set несут Bearer, а чтение списка остаётся открытым`() = runTest {
        client.sessionToken = "tok-123"

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"routes":["87.240.129.0/24"]}"""))
        api.resolve(listOf("vk.com"))
        assertEquals("Bearer tok-123", server.takeRequest().getHeader("Authorization"))

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true,"count":1}"""))
        api.set(listOf(BypassRoute("87.240.129.0", 24)))
        assertEquals("Bearer tok-123", server.takeRequest().getHeader("Authorization"))

        // /app/bypass-routes открыт по замыслу — заголовок туда не шлём.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"routes":[]}"""))
        api.fetchRoutes()
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    // --- 401: сессия недействительна, а не сбой ---

    @Test
    fun `401 на resolve это NotAuthorized, а не Failed`() = runTest {
        // Слить с Failed значило бы предложить повторить запрос, который будет
        // отвергнут столько же раз, сколько его повторят.
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":{"error":{"code":"unauthorized"}}}"""),
        )
        assertEquals(BypassResolve.NotAuthorized, api.resolve(listOf("vk.com")))
    }

    @Test
    fun `401 на set это NotAuthorized, и запись не считается успешной`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody("""{"detail":{"error":{"code":"unauthorized"}}}"""),
        )
        assertEquals(BypassWrite.NotAuthorized, api.set(listOf(BypassRoute("87.240.129.0", 24))))
    }

    @Test
    fun `разбор кодов resolve и set чист от сети`() {
        // Чистая функция: проверяется без MockWebServer.
        assertEquals(BypassResolve.InvalidTarget, mapResolveStatus(400))
        assertEquals(BypassResolve.NotAuthorized, mapResolveStatus(401))
        assertEquals(BypassResolve.Failed, mapResolveStatus(500))
        assertNull(mapResolveStatus(200))
        assertNull(mapResolveStatus(204))

        assertEquals(BypassWrite.Invalid, mapSetStatus(400))
        assertEquals(BypassWrite.NotAuthorized, mapSetStatus(401))
        assertEquals(BypassWrite.Failed, mapSetStatus(503))
        assertNull(mapSetStatus(200))
    }
}
