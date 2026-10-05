package com.impossi8le.vpnapp.update

import com.impossi8le.vpnapp.core.ui.UpdateUiState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class UpdateRestoreTest {

    private val stale = UpdateVerdictStore.Verdict(latest = 95, minSupported = 90)

    @Test
    fun `сохранённый вердикт блокирует с первого кадра`() {
        // Установлена версия 89, порог 90 — приложение обязано быть заперто
        // ещё до того, как сеть ответит.
        assertEquals(
            UpdateUiState.Available(95, required = true),
            restoredUpdateState(currentVersionCode = 89, verdict = stale, enforceMinSupported = true),
        )
    }

    @Test
    fun `в отладочной сборке сохранённый вердикт не запирает`() {
        assertEquals(
            UpdateUiState.Available(95, required = false),
            restoredUpdateState(currentVersionCode = 89, verdict = stale, enforceMinSupported = false),
        )
    }

    @Test
    fun `без сохранённого вердикта состояние неизвестно`() {
        assertEquals(
            UpdateUiState.Idle,
            restoredUpdateState(currentVersionCode = 89, verdict = null, enforceMinSupported = true),
        )
    }

    @Test
    fun `устаревший вердикт не выдаётся за свежесть`() {
        // В памяти 95, а установлена 95 — судить не о чем, сеть решит.
        assertEquals(
            UpdateUiState.Idle,
            restoredUpdateState(currentVersionCode = 95, verdict = stale, enforceMinSupported = true),
        )
    }
}
