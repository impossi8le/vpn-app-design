package com.impossi8le.vpnapp.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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

    /**
     * Тело ответа, которое пройдёт проверку целостности.
     *
     * Настоящий zip с `AndroidManifest.xml`, а не массив байт: после того как
     * загрузчик начал отвергать повреждённые APK, произвольные байты для него —
     * битый файл, и тест проверял бы не то.
     */
    private fun apkBytes(payload: Int = 4096): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(ByteArray(payload) { (it % 251).toByte() })
            zip.closeEntry()
            // Вторая запись: проверка целостности читает ВСЕ записи, и так
            // ловится порча не только в начале файла.
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(payload / 2) { (it % 97).toByte() })
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun body(bytes: ByteArray): okio.Buffer = okio.Buffer().write(bytes)

    /**
     * Запись БЕЗ сжатия — как нативные библиотеки в настоящем APK.
     *
     * Именно их обнуляет оборванная загрузка, и именно на них видно порчу:
     * у сжатой записи испорченные байты иногда распаковываются «успешно».
     */
    private fun apkWithStoredLib(libBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(ByteArray(256) { (it % 251).toByte() })
            zip.closeEntry()

            val entry = ZipEntry("lib/arm64-v8a/libovpn3.so")
            entry.method = ZipEntry.STORED
            entry.size = libBytes.size.toLong()
            entry.compressedSize = libBytes.size.toLong()
            val crc = java.util.zip.CRC32().apply { update(libBytes) }
            entry.crc = crc.value
            zip.putNextEntry(entry)
            zip.write(libBytes)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** Огрызок прошлой попытки: столько-то байт и метка содержимого. */
    private fun seedPartial(version: Int, bytes: ByteArray, etag: String = "\"v1\"") {
        ApkDownloader.partFile(cacheDir, version).writeBytes(bytes)
        ApkDownloader.etagFile(cacheDir, version).writeText(etag)
    }

    @Test
    fun `скачанный apk лежит в кеше целиком`() = runTest {
        val apk = apkBytes(2048)
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(apk)))

        val file = downloader().download(server.url("/app.apk").toString(), 7).getOrThrow()

        assertTrue(file.exists(), "файл должен остаться на диске")
        assertTrue(file.readBytes().contentEquals(apk), "содержимое должно совпасть побайтово")
        assertTrue(file.name.endsWith(".apk"), "имя должно нести .apk: ${file.name}")
    }

    @Test
    fun `прогресс доходит до 100`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(apkBytes())))
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
        // Настоящий APK, разрезанный на две части: склейка обязана дать ровно
        // исходный файл, иначе проверка целостности отвергнет результат.
        val apk = apkBytes(4096)
        val cut = apk.size / 3
        seedPartial(7, apk.copyOfRange(0, cut))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $cut-${apk.size - 1}/${apk.size}")
                .setBody(body(apk.copyOfRange(cut, apk.size))),
        )

        val file = downloader().download(server.url("/app.apk").toString(), 7).getOrThrow()

        val request = server.takeRequest()
        assertEquals(
            "bytes=$cut-",
            request.getHeader("Range"),
            "без Range сервер отдаст файл заново, и докачки не будет",
        )
        assertEquals("\"v1\"", request.getHeader("If-Range"), "If-Range бережёт от подмены сборки")
        assertTrue(
            file.readBytes().contentEquals(apk),
            "файл должен быть склейкой огрызка и докачанного куска",
        )
    }

    @Test
    fun `сервер без поддержки докачки — качаем заново, а не склеиваем мусор`() = runTest {
        // 200 на запрос с Range значит, что сервер вернул файл ЦЕЛИКОМ. Если
        // дописать его к огрызку, получится битый APK, который установщик
        // отвергнет с невнятной ошибкой.
        val apk = apkBytes(4096)
        seedPartial(7, apk.copyOfRange(0, 100))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(apk)))

        val file = downloader().download(server.url("/app.apk").toString(), 7).getOrThrow()

        assertTrue(file.readBytes().contentEquals(apk), "файл должен быть ровно тем, что отдал сервер")
    }

    @Test
    fun `неудачная попытка не стирает огрызок прошлой`() = runTest {
        // Скачали половину, сеть отвалилась. Огрызок обязан уцелеть: удалив его,
        // следующая попытка начнёт стомегабайтную закачку с нуля.
        val apk = apkBytes(4096)
        seedPartial(7, apk.copyOfRange(0, 100))
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
        val apk = apkBytes(4096)
        val cut = apk.size / 2
        seedPartial(7, apk.copyOfRange(0, cut))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $cut-${apk.size - 1}/${apk.size}")
                .setBody(body(apk.copyOfRange(cut, apk.size))),
        )

        downloader().download(server.url("/app.apk").toString(), 7) { seen += it }.getOrThrow()

        assertTrue(
            seen.first() >= 45,
            "первый кадр докачки должен быть около половины, а не нулём: $seen",
        )
        assertEquals(100, seen.last())
    }

    @Test
    fun `огрызок другой версии не подставляется в новую закачку`() = runTest {
        // Релизы публикуются отдельными тегами и нумерация растёт: огрызок
        // версии 7 к файлу версии 8 отношения не имеет, и склеивать их нельзя.
        seedPartial(7, ByteArray(100) { 1 })
        val apk = apkBytes(4096)
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(apk)))

        val file = downloader().download(server.url("/app.apk").toString(), 8).getOrThrow()

        assertNull(server.takeRequest().getHeader("Range"), "для новой версии докачки быть не должно")
        assertTrue(file.readBytes().contentEquals(apk))
    }

    @Test
    fun `clearStale берегёт огрызок текущей версии и убирает чужие`() {
        val mine = ApkDownloader.partFile(cacheDir, 7).apply { writeBytes(ByteArray(10)) }
        val mineEtag = ApkDownloader.etagFile(cacheDir, 7).apply { writeText("\"v1\"") }
        val alien = ApkDownloader.partFile(cacheDir, 6).apply { writeBytes(ByteArray(10)) }
        val alienEtag = ApkDownloader.etagFile(cacheDir, 6).apply { writeText("\"v0\"") }
        val kept = File(cacheDir, "keep.txt").apply { writeText("x") }

        ApkDownloader(OkHttpClient(), cacheDir).clearStale(7)

        assertTrue(mine.exists(), "огрызок ЭТОЙ версии нужен для докачки — его не трогаем")
        assertTrue(mineEtag.exists(), "метка нужна вместе с огрызком")
        assertTrue(!alien.exists(), "огрызок другой версии только занимает место")
        assertTrue(!alienEtag.exists(), "метка чужой версии едет с её огрызком")
        assertTrue(kept.exists(), "посторонние файлы не трогаем")
    }

    @Test
    fun `скачанное обновление не стирается как чужое`() {
        // Живой дефект 1.0.119. Установлена сборка 119, скачано обновление 123:
        // файл лежит как update-123.apk, а `BuildConfig.VERSION_CODE` равен 119.
        // Пока в список нужных передавали только установленную версию, КАЖДЫЙ
        // запуск приложения стирал скачанное обновление — баннер говорил
        // «скачано», а кнопка не находила файл и молчала.
        val downloaded = File(cacheDir, "update-123.apk").apply { writeBytes(ByteArray(50)) }

        ApkDownloader(OkHttpClient(), cacheDir).clearStale(119, 123)

        assertTrue(
            downloaded.exists(),
            "файл ПРЕДЛОЖЕННОЙ версии — не мусор: из-за его удаления кнопка «Установить» молчала",
        )
    }

    @Test
    fun `без известной версии обновления чужой файл убирается`() {
        // Обратная сторона: пока версия обновления неизвестна, нужную не угадать,
        // и старый файл честнее считать мусором, чем держать 60 МБ неизвестно чего.
        val old = File(cacheDir, "update-120.apk").apply { writeBytes(ByteArray(50)) }

        ApkDownloader(OkHttpClient(), cacheDir).clearStale(119)

        assertTrue(!old.exists(), "без известной версии обновления файл не нужен")
    }

    @Test
    fun `удачный финиш убирает метку вместе с огрызком`() = runTest {
        val apk = apkBytes(4096)
        val cut = apk.size / 2
        seedPartial(7, apk.copyOfRange(0, cut))
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $cut-${apk.size - 1}/${apk.size}")
                .setBody(body(apk.copyOfRange(cut, apk.size))),
        )

        downloader().download(server.url("/app.apk").toString(), 7).getOrThrow()

        assertTrue(!ApkDownloader.partFile(cacheDir, 7).exists(), "огрызок переименован в готовый APK")
        assertTrue(!ApkDownloader.etagFile(cacheDir, 7).exists(), "метка осталась бы висеть навсегда")
    }

    // --- readyFile: готовность привязана к ВЕРСИИ файла ---

    @Test
    fun `файл другой версии не считается готовым`() {
        // Живой дефект (1.0.119): скачали сборку 120, на сервере уже 123. Воркер
        // помнил «успех» и не знал версии, поэтому баннер писал «Обновление
        // скачано», а установка искала update-123.apk, не находила и молчала.
        File(cacheDir, "update-120.apk").writeBytes(apkBytes())

        assertNull(
            ApkDownloader.readyFile(cacheDir, 123),
            "файл чужой версии не должен выглядеть готовым к установке",
        )
    }

    @Test
    fun `файл текущей версии считается готовым`() {
        File(cacheDir, "update-123.apk").writeBytes(apkBytes())

        assertNotNull(
            ApkDownloader.readyFile(cacheDir, 123),
            "файл своей версии — это и есть готовность",
        )
    }

    @Test
    fun `пустой файл не считается готовым`() {
        // Обрезанный до нуля файл установщик отвергнет, а баннер уже сказал бы
        // «скачано». Пустота — не готовность.
        File(cacheDir, "update-123.apk").writeBytes(ByteArray(0))

        assertNull(ApkDownloader.readyFile(cacheDir, 123))
    }

    // --- Проверка целостности скачанного APK (issue #29) ---

    @Test
    fun `целый apk не даёт ошибки целостности`() {
        val apk = File(cacheDir, "good.apk").apply { writeBytes(apkBytes()) }

        assertNull(ApkDownloader.apkIntegrityError(apk), "годный файл не должен считаться битым")
    }

    @Test
    fun `обнулённый кусок внутри apk распознаётся как порча`() {
        // Живой дефект: Chrome сообщил «скачан 1 файл», размер совпал с серверным,
        // но 189 718 байт внутри libovpn3.so пришли нулями. Установщик ответил
        // «Приложение не установлено», а клиент считал файл готовым — он сверял
        // только размер.
        val lib = ByteArray(16384) { (it % 251).toByte() }
        val bytes = apkWithStoredLib(lib)
        // Портим середину несжатой записи — ровно то, что делает оборванная
        // загрузка с нативной библиотекой.
        val start = bytes.size * 2 / 3
        for (i in start..(start + 2048)) bytes[i] = 0
        val apk = File(cacheDir, "corrupt.apk").apply { writeBytes(bytes) }

        assertNotNull(
            ApkDownloader.apkIntegrityError(apk),
            "файл того же размера, но с обнулённым куском — это порча, а не готовый APK",
        )
    }

    @Test
    fun `обрезанный apk распознаётся как порча`() {
        val apk = File(cacheDir, "truncated.apk")
        val bytes = apkBytes(8192)
        apk.writeBytes(bytes.copyOfRange(0, bytes.size / 2))

        assertNotNull(
            ApkDownloader.apkIntegrityError(apk),
            "обрезанный архив не должен считаться годным",
        )
    }

    @Test
    fun `пустой файл не считается годным apk`() {
        val apk = File(cacheDir, "empty.apk").apply { writeBytes(ByteArray(0)) }

        assertNotNull(ApkDownloader.apkIntegrityError(apk))
    }

    @Test
    fun `zip без манифеста не считается apk`() {
        // Установщик такой файл отвергнет: в APK обязан быть AndroidManifest.xml.
        val notApk = File(cacheDir, "not-apk.apk")
        ZipOutputStream(notApk.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("readme.txt"))
            zip.write("привет".toByteArray())
            zip.closeEntry()
        }

        assertNotNull(ApkDownloader.apkIntegrityError(notApk), "архив без манифеста — не APK")
    }

    @Test
    fun `битый файл не объявляется готовым при скачивании`() = runTest {
        // Сквозная проверка: сервер отдаёт повреждённый APK (размер честный, но
        // внутри дыра). Загрузчик обязан вернуть неудачу, а не файл — иначе
        // установщик скажет «Приложение не установлено» без объяснения.
        val lib = ByteArray(16384) { (it % 251).toByte() }
        val bytes = apkWithStoredLib(lib)
        val start = bytes.size * 2 / 3
        for (i in start..(start + 2048)) bytes[i] = 0
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(bytes)))

        val result = downloader().download(server.url("/app.apk").toString(), 7)

        assertTrue(result.isFailure, "повреждённый APK не должен считаться скачанным")
        assertTrue(
            cacheDir.listFiles().orEmpty().none { it.name == "update-7.apk" },
            "битый файл не должен остаться под видом готового",
        )
    }
}
