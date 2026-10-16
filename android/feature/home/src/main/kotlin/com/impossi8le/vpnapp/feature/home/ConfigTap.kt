package com.impossi8le.vpnapp.feature.home

import com.impossi8le.vpnapp.domain.model.ConnectionStatus

/**
 * Как тап по строке подключения доходит до обработчика.
 *
 * **Почему отдельное решение, а не ветка в разметке.** Раньше экран сам выбирал
 * между «выбрать» и «переключиться» по признаку «эта строка уже выделена»: тап по
 * выделенной шёл в переключение, по любой другой — в тихий выбор. Из-за этого
 * подтверждение показывалось только на одной строке — той, что уже была выбрана
 * (issue #19): чтобы получить попап, надо было тапнуть строку (она тихо
 * выделялась) и тапнуть её ВТОРОЙ раз. Внешне это и выглядело как «попап
 * работает только в определённой зоне».
 *
 * Признак «выделена» для этого не годится: выделение — про то, чем подключаться
 * ДАЛЬШЕ, а решение о переключении — про то, что поднято СЕЙЧАС. Различитель —
 * есть ли живое соединение. Тогда путь выбранной и невыбранной строки сходится в
 * одно решение, и оно проверяется тестом без рендера и без Android.
 *
 * Чистая функция: только вход и исход, ни состояния, ни ввода-вывода, ни Compose.
 */
sealed interface ConfigTapRouting {
    /** Ничего не поднято: тап лишь выбирает, ЧЕМ подключаться дальше. */
    data class Choose(val id: String) : ConfigTapRouting

    /**
     * Тап по подключению, которое уже работает. Переключаться некуда: спрашивать
     * подтверждение на то, что и так поднято, — вопрос без предмета.
     */
    data class AlreadyRunning(val id: String) : ConfigTapRouting

    /** Есть живое соединение и цель другая: путь смены подключения. */
    data class Switch(val id: String) : ConfigTapRouting
}

/**
 * Что делает тап по строке. [liveConnection] — есть ли живое соединение сейчас
 * (см. [hasLiveConnection]); [selectedId] — подключение, которое работает.
 */
fun routeConfigTap(
    tappedId: String,
    selectedId: String?,
    liveConnection: Boolean,
): ConfigTapRouting = when {
    !liveConnection -> ConfigTapRouting.Choose(tappedId)
    tappedId == selectedId -> ConfigTapRouting.AlreadyRunning(tappedId)
    else -> ConfigTapRouting.Switch(tappedId)
}

/**
 * Идёт ли работа с ЖИВЫМ соединением: туннель поднят или поднимается.
 *
 * Поднимается он тоже «живой»: в `Connecting`/`Recovering` трафик вот-вот пойдёт
 * через туннель (а в `Recovering` — уже частично идёт напрямую), и смена цели в
 * эти секунды так же рвёт начатое. Поэтому подтверждение уместно и здесь.
 *
 * `Disconnected` и `Failed` живыми НЕ считаются: поднимать нечего, и тап по
 * строке обязан лишь выбрать, чем подключаться, а не спрашивать про обрыв того,
 * чего нет.
 *
 * Отдельно от [ConnectionStatus.isProtected]: «защищено» — про доказанный замер
 * (§6), здесь же только про существование соединения. Смешивать нельзя.
 */
val ConnectionStatus.hasLiveConnection: Boolean
    get() = when (this) {
        ConnectionStatus.Disconnected, is ConnectionStatus.Failed -> false
        else -> true
    }
