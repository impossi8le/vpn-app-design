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
    fun `успех основного источника — запасной не трогается`() = runTest {
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

        assertEquals("https://srv/95.apk", info.apkUrl)
        assertTrue(!fallbackCalled, "запасной источник не должен вызываться при успехе основного")
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
}
