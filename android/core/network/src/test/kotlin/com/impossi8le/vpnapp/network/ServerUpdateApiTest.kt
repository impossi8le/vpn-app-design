package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.update.UpdateError
import com.impossi8le.vpnapp.domain.update.UpdateException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Источник версий — наш сервер, а не GitHub.
 *
 * Тег синтезируется как `android-v<номер>`: разбор тега остаётся правилом
 * домена (`parseReleaseTag`), и серверный ответ не должен вводить второй формат.
 */
class ServerUpdateApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ServerUpdateApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = ServerUpdateApi(ApiClient(baseUrl = server.url("/api/v1").toString().trimEnd('/')))
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `версия и ссылка на apk разбираются`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"version":95,"apk_url":"https://example.com/vpn-95.apk"}""",
            ),
        )

        val info = api.latestRelease().getOrThrow()
        assertEquals("android-v95", info.tagName)
        assertEquals("https://example.com/vpn-95.apk", info.apkUrl)

        val request = server.takeRequest()
        assertEquals("/api/v1/app/latest?platform=android", request.path)
    }

    @Test
    fun `ответ без ссылки на apk — неполный ответ, а не обновление`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"version":95}"""))

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        assertTrue(error is UpdateError.Unexpected, "ссылки нет => предлагать нечего, было $error")
    }

    @Test
    fun `нет релизов — это не ошибка связи`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"release_not_found"}}"""))

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        assertEquals(UpdateError.NotFound, error)
    }

    @Test
    fun `обрыв связи — сбой сети`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        assertEquals(UpdateError.NetworkUnavailable, error)
    }

    @Test
    fun `порог читается числом, мусор даёт null`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("90"))
        assertEquals(90, api.minSupported())

        server.enqueue(MockResponse().setResponseCode(200).setBody("не число"))
        assertNull(api.minSupported())

        server.enqueue(MockResponse().setResponseCode(500).setBody(""))
        assertNull(api.minSupported())
    }

    @Test
    fun `версия строкой тоже принимается`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"version":"95","apk_url":"https://example.com/vpn-95.apk"}""",
            ),
        )

        val info = api.latestRelease().getOrThrow()
        assertEquals("android-v95", info.tagName)
    }

    @Test
    fun `не-404 и не-2xx — неожиданный сбой, а не отсутствие релиза`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        assertEquals(UpdateError.Unexpected(statusCode = 500), error)
    }

    @Test
    fun `тело не JSON — неожиданный ответ, а не обновление`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>oops</html>"))

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        assertTrue(error is UpdateError.Unexpected, "не JSON => предлагать нечего, было $error")
    }
}
