package com.impossi8le.vpnapp.core.ui

/**
 * Что показать по итогам проверки обновления.
 *
 * Живёт в `core:ui`, а не рядом с чекером, потому что читают его экраны двух
 * модулей (`feature:account` и `feature:home`), а `app` им не виден: зависимости
 * идут в одну сторону, и обратной быть не может.
 */
sealed interface UpdateUiState {
    /** Проверка ещё не запускалась. */
    data object Idle : UpdateUiState

    /** Проверка идёт. */
    data object Checking : UpdateUiState

    /** Обновляться не нужно. */
    data object UpToDate : UpdateUiState

    /** Есть версия новее. */
    data class Available(val versionCode: Int) : UpdateUiState

    /** Проверить не удалось: показываем причину, а не «версия свежая». */
    data class Failed(val reason: String) : UpdateUiState
}
