package com.impossi8le.vpnapp.update

import com.impossi8le.vpnapp.core.ui.UpdateUiState
import com.impossi8le.vpnapp.domain.update.UpdateStatus
import com.impossi8le.vpnapp.domain.update.isVersionSupported
import com.impossi8le.vpnapp.domain.update.updateStatus

/**
 * Состояние обновления из сохранённого вердикта — для самого первого кадра.
 *
 * Отдельная чистая функция, а не логика внутри `MainActivity`: здесь решается,
 * запереть приложение или нет, и это решение должно проверяться тестом, а не
 * только руками на телефоне.
 *
 * Устаревший вердикт (в памяти версия не новее установленной) даёт [UpdateUiState.Idle],
 * а не «обновления нет»: сеть ещё ответит, и выдумывать по нему свежесть нельзя.
 */
fun restoredUpdateState(
    currentVersionCode: Int,
    verdict: UpdateVerdictStore.Verdict?,
    enforceMinSupported: Boolean,
): UpdateUiState {
    if (verdict == null) return UpdateUiState.Idle
    val status = updateStatus(currentVersionCode, "android-v${verdict.latest}")
    return when (status) {
        is UpdateStatus.Available -> UpdateUiState.Available(
            versionCode = status.versionCode,
            required = enforceMinSupported &&
                !isVersionSupported(currentVersionCode, verdict.minSupported),
        )
        // Та же версия или младше сохранённого — память устарела, решать будет сеть.
        else -> UpdateUiState.Idle
    }
}
