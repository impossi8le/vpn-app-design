package com.impossi8le.vpnapp.update

import androidx.work.WorkInfo
import com.impossi8le.vpnapp.core.ui.UpdateDownloadState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Перевод состояния работы в состояние экрана.
 *
 * Проверяется здесь, а не через `WorkManager`: поднять настоящий менеджер в
 * JVM-тесте нельзя, а именно это отображение решает, что увидит человек —
 * «идёт», «на паузе», «готово» или «повторить».
 */
class UpdateDownloadSchedulerTest {

    @Test
    fun `running показывает процент загрузки`() {
        assertEquals(
            UpdateDownloadState.Downloading(42),
            UpdateDownloadScheduler.toState(WorkInfo.State.RUNNING, 42),
        )
    }

    @Test
    fun `enqueued после паузы показывает прежний процент, а не ноль`() {
        // Ноль после паузы читался бы как «прогресс потерян» — а он не потерян:
        // огрызок на диске, и продолжение пойдёт с него.
        assertEquals(
            UpdateDownloadState.Paused(73),
            UpdateDownloadScheduler.toState(WorkInfo.State.ENQUEUED, 73),
        )
    }

    @Test
    fun `succeeded даёт готовность к установке`() {
        // Четвёртое состояние, ради которого задача и делалась: без него экран
        // возвращался к «Обновить», и скачанный файл становился недостижим.
        assertEquals(
            UpdateDownloadState.Ready,
            UpdateDownloadScheduler.toState(WorkInfo.State.SUCCEEDED, 100),
        )
    }

    @Test
    fun `failed не выдаёт себя за готовность`() {
        val state = UpdateDownloadScheduler.toState(WorkInfo.State.FAILED, 80)

        assertEquals(UpdateDownloadState.Failed("скачивание не удалось"), state)
    }

    @Test
    fun `отменённая работа возвращает баннер в исходный вид`() {
        // Сюда приходит «Позже»: пользователь отложил, и никакого «готово» быть
        // не должно — файла на диске нет.
        assertEquals(
            UpdateDownloadState.Idle,
            UpdateDownloadScheduler.toState(WorkInfo.State.CANCELLED, 50),
        )
    }
}
