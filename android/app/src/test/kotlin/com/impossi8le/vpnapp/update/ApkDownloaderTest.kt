package com.impossi8le.vpnapp.update

import java.io.File
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    /** Огрызок прошлой попытки: столько-то байт и метка содержимого. */
    private fun seedPartial(version: Int, bytes: ByteArray, etag: String = "\"v1\"") {
        ApkDownloader.partFile(cacheDir, version).writeBytes(bytes)
        ApkDownloader.etagFile(cacheDir, version).writeText(etag)
    }

    @Test
    fun `скачанный apk лежит в кеше целиком`() = runTest {
        val payload = ByteArray(2048) { it.toByte() }
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload)),
        )

        val file = downloader().download(server.url("/app.apk").toString(), 7).getOrThrow()

        assertTrue(file.exists(), "файл должен остаться на диске")
        assertTrue(file.readBytes().contentEquals(payload), "содержимое должно совпасть побайтово")
        assertTrue(file.name.endsWith(".apk"), "имя должно нести .apk: ${file.name}")
    }

    @Test
    fun `прогресс доходит до 100`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("x".repeat(1024)))
        val percents = mutableListOf<Int>()

        downloader()
            .download(server.url("/app.apk").toString(), 7) { percents += it }
            .getOrThrow()

        assertEquals(100, percents.last())
    }

    @Test
    fun `обрыв не оставляет половину файла под видом готового`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = downloader().download(server.url("/app.apk").toString(), 7)

        assertTrue(result.isFailure, "500 — это провал, а не файл")
        assertTrue(
            cacheDir.listFiles().orEmpty().none { it.name.endsWith(".apk") },
            "недокачанный файл не должен выглядеть готовым: ${cacheDir.list()?.toList()}",
        )
    }

    @Test
    fun `клиент скачивания не ограничивает весь вызов по времени`() {
        // Прямая проверка причины поломки: с callTimeout 45 с стомегабайтный APK
        // не успевает скачаться, и обновление обрывается на середине. Тест
        // падает, если кто-то вернёт сюда общий клиент API.
        val client = com.impossi8le.vpnapp.network.ApiClient.downloadClient()

        assertNull(
            client.callTimeoutMillis.takeIf { it > 0 },
            "у клиента скачивания не должно быть callTimeout: он обрывает долгую закачку",
        )
        assertTrue(client.readTimeoutMillis > 0, "пауза без данных всё же должна быть ограничена")
    }

    // --- Докачка после обрыва (issue #7) ---

    @Test
    fun `докачка просит только недостающий кусок`() = runTest {
        // Уже есть 100 байт из 300. Остаток отдаём ответом 206 — так ведёт себя
        // CDN GitHub, проверено запросом с Range.
        val head = ByteArray(100) { 1 }
        val tail = ByteArray(200) { 2 }
        seedPartial(7, head)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 100-299/300")
                .setBody(okio.Buffer().write(tail)),
        )

        val file = downloader().download(server.url("/app.apk").toString(), 7).getOrThrow()

        val request = server.takeRequest()
        assertEquals(
            "bytes=100-",
            request.getHeader("Range"),
            "без Range сервер отдаст файл заново, и докачки не будет",
        )
        assertEquals("\"v1\"", request.getHeader("If-Range"), "If-Range бережёт от подмены сборки")
        assertTrue(
            file.readBytes().contentEquals(head + tail),
            "файл должен быть склейкой огрызка и докачанного куска",
        )
    }

    @Test
    fun `сервер без поддержки докачки — качаем заново, а не склеиваем мусор`() = runTest {
        // 200 на запрос с Range значит, что сервер вернул файл ЦЕЛИКОМ. Если
        // дописать его к огрызку, получится битый APK, который установщик
        // отвергнет с невнятной ошибкой.
        val payload = ByteArray(300) { 3 }
        seedPartial(7, ByteArray(100) { 1 })
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload)))

        val file = downloader().download(server.url("/app.apk").toString(), 7).getOrThrow()

        assertTrue(file.readBytes().contentEquals(payload), "файл должен быть ровно тем, что отдал сервер")
    }

    @Test
    fun `неудачная попытка не стирает огрызок прошлой`() = runTest {
        // Скачали половину, сеть отвалилась. Огрызок обязан уцелеть: удалив его,
        // следующая попытка начнёт стомегабайтную закачку с нуля.
        seedPartial(7, ByteArray(100) { 1 })
        server.enqueue(MockResponse().setResponseCode(500))

        val result = downloader().download(server.url("/app.apk").toString(), 7)

        assertTrue(result.isFailure, "500 — провал")
        assertTrue(
            ApkDownloader.partFile(cacheDir, 7).exists(),
            "огрызок потерян: следующая попытка начнётся с нуля",
        )
        assertEquals(100L, ApkDownloader.partFile(cacheDir, 7).length(), "огрызок не должен быть обрезан")
    }

    @Test
    fun `прогресс при докачке считается от полного размера, а не от остатка`() = runTest {
        // Иначе после обрыва на 90% строка показала бы «0%» и пошла к 100 заново.
        val seen = mutableListOf<Int>()
        seedPartial(7, ByteArray(100) { 1 })
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 100-299/300")
                .setBody(okio.Buffer().write(ByteArray(200) { 2 })),
        )

        downloader().download(server.url("/app.apk").toString(), 7) { seen += it }.getOrThrow()

        assertTrue(seen.first() >= 33, "первый кадр докачки должен быть около трети, а не нулём: $seen")
        assertEquals(100, seen.last())
    }

    @Test
    fun `огрызок другой версии не подставляется в новую закачку`() = runTest {
        // Релизы публикуются отдельными тегами и нумерация растёт: огрызок
        // версии 7 к файлу версии 8 отношения не имеет, и склеивать их нельзя.
        seedPartial(7, ByteArray(100) { 1 })
        val payload = ByteArray(300) { 9 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(payload)))

        val file = downloader().download(server.url("/app.apk").toString(), 8).getOrThrow()

        assertNull(server.takeRequest().getHeader("Range"), "для новой версии докачки быть не должно")
        assertTrue(file.readBytes().contentEquals(payload))
    }

    @Test
    fun `clearStale берегёт огрызок текущей версии и убирает чужие`() {
        val mine = ApkDownloader.partFile(cacheDir, 7).apply { writeBytes(ByteArray(10)) }
        val mineEtag = ApkDownloader.etagFile(cacheDir, 7).apply { writeText("\"v1\"") }
        val alien = ApkDownloader.partFile(cacheDir, 6).apply { writeBytes(ByteArray(10)) }
        val alienEtag = ApkDownloader.etagFile(cacheDir, 6).apply { writeText("\"v0\"") }
        val kept = File(cacheDir, "keep.txt").apply { writeText("x") }

        ApkDownloader(OkHttpClient(), cacheDir).clearStale(currentVersionCode = 7)

        assertTrue(mine.exists(), "огрызок ЭТОЙ версии нужен для докачки — его не трогаем")
        assertTrue(mineEtag.exists(), "метка нужна вместе с огрызком")
        assertTrue(!alien.exists(), "огрызок другой версии только занимает место")
        assertTrue(!alienEtag.exists(), "метка чужой версии едет с её огрызком")
        assertTrue(kept.exists(), "посторонние файлы не трогаем")
    }

    @Test
    fun `удачный финиш убирает метку вместе с огрызком`() = runTest {
        seedPartial(7, ByteArray(100) { 1 })
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 100-299/300")
                .setBody(okio.Buffer().write(ByteArray(200) { 2 })),
        )

        downloader().download(server.url("/app.apk").toString(), 7).getOrThrow()

        assertTrue(!ApkDownloader.partFile(cacheDir, 7).exists(), "огрызок переименован в готовый APK")
        assertTrue(!ApkDownloader.etagFile(cacheDir, 7).exists(), "метка осталась бы висеть навсегда")
    }
}
