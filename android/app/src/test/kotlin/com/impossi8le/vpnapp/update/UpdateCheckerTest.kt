package com.impossi8le.vpnapp.update

import com.impossi8le.vpnapp.core.ui.UpdateUiState
import com.impossi8le.vpnapp.domain.update.ReleaseInfo
import com.impossi8le.vpnapp.domain.update.UpdateError
import com.impossi8le.vpnapp.domain.update.UpdateException
import com.impossi8le.vpnapp.domain.update.UpdateService
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class FakeService(private val result: Result<ReleaseInfo>) : UpdateService {
    override suspend fun latestRelease(): Result<ReleaseInfo> = result
}

class UpdateCheckerTest {

    @Test
    fun `новая версия в релизе — состояние Available`() = runTest {
        val checker = UpdateChecker(
            currentVersionCode = 12,
            service = FakeService(Result.success(ReleaseInfo("android-v13", "https://e/a.apk"))),
        )

        assertEquals(UpdateUiState.Available(13), checker.check())
    }

    @Test
    fun `та же версия — UpToDate`() = runTest {
        val checker = UpdateChecker(
            currentVersionCode = 12,
            service = FakeService(Result.success(ReleaseInfo("android-v12", "https://e/a.apk"))),
        )

        assertEquals(UpdateUiState.UpToDate, checker.check())
    }

    @Test
    fun `чужой тег — Failed, а не обновление есть`() = runTest {
        val checker = UpdateChecker(
            currentVersionCode = 12,
            service = FakeService(Result.success(ReleaseInfo("v1.0.5", "https://e/a.apk"))),
        )

        // Ключевое: неизвестность не выдаём за обновление.
        assertTrue(checker.check() is UpdateUiState.Failed)
    }

    @Test
    fun `нет релизов — UpToDate, а не ошибка`() = runTest {
        val checker = UpdateChecker(
            currentVersionCode = 12,
            service = FakeService(Result.failure(UpdateException(UpdateError.NotFound))),
        )

        assertEquals(UpdateUiState.UpToDate, checker.check())
    }

    @Test
    fun `сеть недоступна — Failed с причиной`() = runTest {
        val checker = UpdateChecker(
            currentVersionCode = 12,
            service = FakeService(Result.failure(UpdateException(UpdateError.NetworkUnavailable))),
        )

        val state = checker.check()
        assertTrue(state is UpdateUiState.Failed, "нет связи — честное «не удалось проверить», было $state")
    }
}
