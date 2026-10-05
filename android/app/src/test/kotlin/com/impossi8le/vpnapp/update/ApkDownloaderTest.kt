package com.impossi8le.vpnapp.update

import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ApkDownloaderTest {

    private lateinit var server: MockWebServer

    @TempDir
    lateinit var cacheDir: File

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun downloader() = ApkDownloader(OkHttpClient(), cacheDir)

    @Test
    fun `скачанный apk лежит в кеше целиком`() = runTest {
        val payload = ByteArray(2048) { it.toByte() }
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload)),
        )

        val file = downloader().download(server.url("/app.apk").toString()).getOrThrow()

        assertTrue(file.exists(), "файл должен остаться на диске")
        assertTrue(file.readBytes().contentEquals(payload), "содержимое должно совпасть побайтово")
        assertTrue(file.name.endsWith(".apk"), "имя должно нести .apk: ${file.name}")
    }

    @Test
    fun `прогресс доходит до 100`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(1024)))
        val percents = mutableListOf<Int>()

        downloader().download(server.url("/app.apk").toString()) { percents += it }.getOrThrow()

        assertEquals(100, percents.last())
    }

    @Test
    fun `обрыв не оставляет половину файла под видом готового`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = downloader().download(server.url("/app.apk").toString())

        assertTrue(result.isFailure, "500 — это провал, а не файл")
        assertTrue(
            cacheDir.listFiles().orEmpty().none { it.extension == "apk" },
            "недокачанный файл не должен выглядеть готовым: ${cacheDir.list()?.toList()}",
        )
    }

    @Test
    fun `clearStale убирает только недокачанное`() {
        val part = File(cacheDir, "update.apk.part").apply { writeText("half") }
        val kept = File(cacheDir, "keep.txt").apply { writeText("x") }

        ApkDownloader(OkHttpClient(), cacheDir).clearStale()

        assertTrue(!part.exists(), ".part должен быть удалён")
        assertTrue(kept.exists(), "посторонние файлы не трогаем")
    }
}
