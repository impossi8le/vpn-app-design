package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.update.UpdateError
import com.impossi8le.vpnapp.domain.update.UpdateException
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UpdateApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: UpdateApi

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = UpdateApi(
            ApiClient(baseUrl = server.url("/").toString().trimEnd('/')),
            releasesUrl = server.url("/repos/impossi8le/vpn-app-design/releases/latest").toString(),
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `релиз с apk-ассетом разбирается в тег и ссылку`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"tag_name":"android-v42","assets":[
                  {"name":"vpn-app-android-42.apk",
                   "browser_download_url":"https://example.com/app.apk"}
                ]}
                """.trimIndent(),
            ),
        )

        val info = api.latestRelease().getOrThrow()
        assertEquals("android-v42", info.tagName)
        assertEquals("https://example.com/app.apk", info.apkUrl)
    }

    @Test
    fun `релиз без apk-ассета — не обновление, а сбой ответа`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"tag_name":"android-v42","assets":[{"name":"notes.txt","browser_download_url":"https://example.com/n.txt"}]}""",
            ),
        )

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        // Скачивать нечего — предлагать обновление с пустой ссылкой нельзя.
        assertTrue(error is UpdateError.Unexpected, "ассета нет => ответ неполный, было $error")
    }

    @Test
    fun `нет релизов — это не ошибка связи`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        assertEquals(UpdateError.NotFound, error)
    }

    @Test
    fun `лимит анонимных запросов отличается от отсутствия релиза`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"message":"API rate limit exceeded"}"""),
        )

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        assertEquals(UpdateError.RateLimited, error)
    }

    @Test
    fun `битое тело не роняет парсер`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("не json"))

        val error = (api.latestRelease().exceptionOrNull() as UpdateException).error
        assertTrue(
            error is UpdateError.Unexpected || error is UpdateError.NetworkUnavailable,
            "битое тело — это сбой обращения, было $error",
        )
    }
}
