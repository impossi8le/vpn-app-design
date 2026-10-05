package com.impossi8le.vpnapp.update

import com.impossi8le.vpnapp.core.ui.UpdateUiState
import com.impossi8le.vpnapp.domain.update.UpdateError
import com.impossi8le.vpnapp.domain.update.UpdateException
import com.impossi8le.vpnapp.domain.update.UpdateService
import com.impossi8le.vpnapp.domain.update.UpdateStatus
import com.impossi8le.vpnapp.domain.update.updateStatus

/**
 * Сведение ответа сервиса и правила домена в состояние экрана.
 *
 * Различие «обновления нет» и «проверить не удалось» здесь принципиально:
 * первое — утверждение о свежести, второе — честное незнание. Показать второе
 * как первое значило бы сказать пользователю, что у него всё актуально, не имея
 * на это оснований.
 */
class UpdateChecker(
    private val currentVersionCode: Int,
    private val service: UpdateService,
) {

    suspend fun check(): UpdateUiState {
        val result = service.latestRelease()
        val error = (result.exceptionOrNull() as? UpdateException)?.error
        if (error != null) {
            return when (error) {
                // Релизов нет — обновляться не с чего, это не сбой проверки.
                UpdateError.NotFound -> UpdateUiState.UpToDate
                UpdateError.NetworkUnavailable -> UpdateUiState.Failed("нет связи с GitHub")
                UpdateError.RateLimited -> UpdateUiState.Failed("GitHub ограничил запросы, попробуйте позже")
                is UpdateError.Unexpected -> UpdateUiState.Failed("GitHub ответил неожиданно")
            }
        }

        val info = result.getOrNull() ?: return UpdateUiState.Failed("не удалось проверить")
        return when (val status = updateStatus(currentVersionCode, info.tagName)) {
            is UpdateStatus.Available -> UpdateUiState.Available(status.versionCode)
            UpdateStatus.UpToDate -> UpdateUiState.UpToDate
            UpdateStatus.Unknown -> UpdateUiState.Failed("формат релиза не распознан")
        }
    }
}
