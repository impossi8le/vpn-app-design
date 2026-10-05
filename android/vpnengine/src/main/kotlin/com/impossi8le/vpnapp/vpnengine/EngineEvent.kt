package com.impossi8le.vpnapp.vpnengine

/**
 * Имена событий ядра OpenVPN 3, как они приходят в `ClientAPI_Event.getName()`.
 *
 * Это чистые строки без зависимости от JNI: функция [engineStateFrom] проверяется
 * JVM-тестом, а сам разбор события остаётся в [EngineClient].
 */
object EngineEvent {
    const val CONNECTED = "CONNECTED"
    const val RECONNECTING = "RECONNECTING"
    const val DISCONNECTED = "DISCONNECTED"
}

/**
 * Состояние движка по имени события ядра.
 *
 * **Почему сравнение точное (`==`), а не `contains`.** Имена событий ядра не
 * вложены друг в друга, но одно из них СОДЕРЖИТ другое как подстроку:
 * `"DISCONNECTED".contains("CONNECTED") == true`. Прежняя реализация сравнивала
 * через `contains`, поэтому событие разрыва `DISCONNECTED` распознавалось как
 * `CONNECTED` — то есть «туннель поднят». Это была не косметика: после
 * отключения сервис присылал `ESTABLISHED`, экран возвращался к состоянию
 * «туннель поднят / защита не пройдена / Отключить», хотя интерфейс `tun0` уже
 * исчез. Пользователь думал, что первое нажатие не сработало, и жал второй раз —
 * а второй тап поднимал новый туннель. Ошибка проверена на устройстве по логу:
 * строка `event: name=DISCONNECTED` и сразу за ней `onServiceState: ESTABLISHED`.
 *
 * Неизвестные имена (CONNECTING, GET_CONFIG, ASSIGN_IP, ADD_ROUTES, RESOLVE,
 * WAIT) дают `null`: отдельного состояния для них нет — пользователю важно «идёт
 * процесс», а не его фаза. Незнакомое имя безопаснее считать «нет состояния»,
 * чем «подключено».
 */
fun engineStateFrom(eventName: String): EngineState? = when (eventName) {
    EngineEvent.CONNECTED -> EngineState.Connected
    EngineEvent.RECONNECTING -> EngineState.Reconnecting
    EngineEvent.DISCONNECTED -> EngineState.Disconnected
    else -> null
}
