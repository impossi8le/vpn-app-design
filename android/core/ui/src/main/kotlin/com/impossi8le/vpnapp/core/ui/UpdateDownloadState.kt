package com.impossi8le.vpnapp.core.ui

/**
 * Состояние скачивания обновления, как его видит экран.
 *
 * Отдельно от [UpdateUiState]: тот про «есть ли новая версия», этот — про сам
 * файл. Смешивать их значило бы будить баннер обновления чужой новостью.
 *
 * **[Ready] существует потому, что кнопка обязана жить после скачивания.**
 * Раньше после успеха прогресс гасился, и экран возвращался к «Обновить» —
 * путь к установке терялся вместе со скачанным файлом.
 */
sealed interface UpdateDownloadState {

    /** Ничего не качается и скачанного нет. */
    data object Idle : UpdateDownloadState

    /** Загрузка идёт: [percent] от 0 до 100. */
    data class Downloading(val percent: Int) : UpdateDownloadState

    /**
     * Загрузка приостановлена пользователем. Огрызок на диске сохранён, и
     * продолжение пойдёт с этого места, а не с нуля — поэтому процент
     * запоминается и показывается снова.
     */
    data class Paused(val percent: Int) : UpdateDownloadState

    /** Файл скачан целиком и ждёт установки. */
    data object Ready : UpdateDownloadState

    /** Скачивание сорвалось; [reason] — короткая причина для человека. */
    data class Failed(val reason: String) : UpdateDownloadState
}
