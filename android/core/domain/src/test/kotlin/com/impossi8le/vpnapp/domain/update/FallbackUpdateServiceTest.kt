package com.impossi8le.vpnapp.domain.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Наш сервер первый, GitHub — только если сервер не ответил.
 *
 * Так переход на свой хостинг не роняет раздачу в день выката: пока серверные
 * эндпоинты не готовы, обновления продолжают приходить с GitHub, а как только
 * сервер заговорит — используется он.
 */
class FallbackUpdateServiceTest {

    private fun service(release: Result<ReleaseInfo>, min: Int?) = object : UpdateService {
        override suspend fun latestRelease(): Result<ReleaseInfo> = release
        override suspend fun minSupported(): Int? = min
    }

    @Test
    fun `успех основного источника — отдаётся его версия`() = runTest {
        // Запасной теперь спрашивается и при успехе основного: он нужен, чтобы
        // заметить ОТСТАВАНИЕ сервера (issue #33). Но исход здесь прежний —
        // побеждает версия основного, потому что она новее.
        val primary = service(Result.success(ReleaseInfo("android-v95", "https://srv/95.apk")), 90)
        var fallbackCalled = false
        val fallback = object : UpdateService {
            override suspend fun latestRelease(): Result<ReleaseInfo> {
                fallbackCalled = true
                return Result.success(ReleaseInfo("android-v1", "https://gh/1.apk"))
            }

            override suspend fun minSupported(): Int? = 1
        }

        val info = FallbackUpdateService(primary, fallback).latestRelease().getOrThrow()

        assertEquals("https://srv/95.apk", info.apkUrl, "более новая версия — у основного")
        assertTrue(fallbackCalled, "запасной спрашивается, чтобы заметить отставание сервера")
    }

    @Test
    fun `отказ основного — берём запасной`() = runTest {
        val primary = service(
            Result.failure(UpdateException(UpdateError.NetworkUnavailable)),
            null,
        )
        val fallback = service(Result.success(ReleaseInfo("android-v94", "https://gh/94.apk")), null)

        val info = FallbackUpdateService(primary, fallback).latestRelease().getOrThrow()

        assertEquals("https://gh/94.apk", info.apkUrl)
    }

    @Test
    fun `оба отказали — отказ`() = runTest {
        val primary = service(Result.failure(UpdateException(UpdateError.NetworkUnavailable)), null)
        val fallback = service(Result.failure(UpdateException(UpdateError.Unexpected(500))), null)

        val result = FallbackUpdateService(primary, fallback).latestRelease()

        assertTrue(result.isFailure)
        val error = (result.exceptionOrNull() as UpdateException).error
        assertEquals(UpdateError.NetworkUnavailable, error, "при отказе обоих отдаём ошибку основного источника")
    }

    @Test
    fun `отмена запасного пробрасывается, а не превращается в Result failure`() {
        val primary = service(Result.failure(UpdateException(UpdateError.NetworkUnavailable)), null)
        val fallback = object : UpdateService {
            override suspend fun latestRelease(): Result<ReleaseInfo> = throw CancellationException("отмена")
            override suspend fun minSupported(): Int? = null
        }

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { FallbackUpdateService(primary, fallback).latestRelease() }
        }
    }

    @Test
    fun `порог берётся с сервера, а при его отсутствии — с запасного`() = runTest {
        assertEquals(90, FallbackUpdateService(service(Result.success(ReleaseInfo("android-v95", "u")), 90), service(Result.success(ReleaseInfo("android-v94", "u")), 80)).minSupported())
        assertEquals(80, FallbackUpdateService(service(Result.success(ReleaseInfo("android-v95", "u")), null), service(Result.success(ReleaseInfo("android-v94", "u")), 80)).minSupported())
    }

    // --- Основной ответил успешно, но СТАРЕЕ запасного (issue #33) ---

    @Test
    fun `устаревший ответ основного не блокирует более новый запасной`() = runTest {
        // Живой случай 2026-10-17: сервер отдавал 134, GitHub — уже 136.
        // Сервер жив и отвечает успешно, поэтому запасной не спрашивался вовсе,
        // и пользователь не узнавал об исправлениях, которые уже лежали в релизах.
        val service = FallbackUpdateService(
            primary = service(Result.success(ReleaseInfo("android-v134", "https://server/vpn-134.apk")), min = 100),
            fallback = service(Result.success(ReleaseInfo("android-v136", "https://github/app.apk")), min = 104),
        )

        val info = service.latestRelease().getOrThrow()

        assertEquals(
            "android-v136",
            info.tagName,
            "более новая версия есть — отставание сервера не повод её скрывать",
        )
        assertEquals(
            "https://github/app.apk",
            info.apkUrl,
            "ссылка обязана вести на ту же версию, что предложена: иначе покажем 136, а скачаем 134",
        )
    }

    @Test
    fun `более новый основной остаётся главным вместе со своей ссылкой`() = runTest {
        // Обратное направление: свой сервер опережает GitHub. Он и главнее, и
        // ссылка берётся его — своя раздача не должна подменяться чужой.
        val service = FallbackUpdateService(
            primary = service(Result.success(ReleaseInfo("android-v140", "https://server/vpn-140.apk")), min = 100),
            fallback = service(Result.success(ReleaseInfo("android-v136", "https://github/app.apk")), min = 104),
        )

        val info = service.latestRelease().getOrThrow()

        assertEquals("android-v140", info.tagName)
        assertEquals("https://server/vpn-140.apk", info.apkUrl)
    }

    @Test
    fun `равные версии оставляют главным основной`() = runTest {
        // Одинаковая версия — не повод уходить на запасной: наша раздача
        // предпочтительнее, а лишний запрос к GitHub ест анонимный лимит.
        val service = FallbackUpdateService(
            primary = service(Result.success(ReleaseInfo("android-v136", "https://server/vpn-136.apk")), min = 100),
            fallback = service(Result.success(ReleaseInfo("android-v136", "https://github/app.apk")), min = 104),
        )

        val info = service.latestRelease().getOrThrow()

        assertEquals("https://server/vpn-136.apk", info.apkUrl, "при равенстве остаёмся на своём сервере")
    }

    @Test
    fun `неразобранный тег основного не мешает запасному`() = runTest {
        // Если у основного тег не нашего формата, сравнивать не с чем — тогда
        // решает запасной, у которого формат верный.
        val service = FallbackUpdateService(
            primary = service(Result.success(ReleaseInfo("какой-то-тег", "https://server/x.apk")), min = null),
            fallback = service(Result.success(ReleaseInfo("android-v136", "https://github/app.apk")), min = 104),
        )

        val info = service.latestRelease().getOrThrow()

        assertEquals("android-v136", info.tagName)
    }
}
