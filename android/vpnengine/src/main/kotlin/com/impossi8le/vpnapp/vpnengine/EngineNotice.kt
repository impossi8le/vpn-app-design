package com.impossi8le.vpnapp.vpnengine

import net.openvpn.ovpn3.ClientAPI_OpenVPNClientHelper

/**
 * Уведомление о лицензии ядра, как его отдаёт само ядро.
 *
 * Строку не пишем руками: ядро OpenVPN 3 (MPL 2.0) отдаёт свой текст через
 * `ClientAPI_OpenVPNClientHelper.copyright()`. Переписывание его в наш код
 * означало бы, что при обновлении ядра уведомление разъедется с фактом.
 */
sealed interface EngineCopyright {

    /** Текст, полученный от ядра. Показывается как есть. */
    data class Text(val text: String) : EngineCopyright

    /**
     * Ядро недоступно или уведомление не получено.
     *
     * **Не выдуманный текст.** На JVM-тестах и в сборке без артефакта движка
     * `libovpn3.so` не загружается, и подстановка «правильного» уведомления
     * выдала бы за факт то, чего библиотека не сообщала. Экран обязан показать
     * причину, а не лицензию.
     */
    data class Unavailable(val reason: String) : EngineCopyright
}

/**
 * Прочитать уведомление о лицензии у ядра.
 *
 * Загрузка библиотеки обязана идти ДО обращения к классам `net.openvpn.ovpn3`:
 * у них нет собственного `System.loadLibrary`, и первое же обращение без
 * загрузки падает с `UnsatisfiedLinkError` (см. [EngineLoader]). Поэтому здесь
 * та же проверка, что и в [OpenVpn3Session]: метод могут позвать раньше
 * подключения — например, с экрана «О сервисе».
 *
 * Вызов обёрнут в `runCatching`: отсутствие ядра — ожидаемый исход на части
 * сборок, а не повод уронить экран с лицензиями.
 */
fun engineCopyright(): EngineCopyright {
    if (!EngineLoader.load()) {
        return EngineCopyright.Unavailable(
            EngineLoader.failureReason ?: "движок недоступен на этом устройстве",
        )
    }
    return runCatching {
        val text = ClientAPI_OpenVPNClientHelper.copyright()
        if (text.isBlank()) {
            EngineCopyright.Unavailable("ядро не вернуло текст уведомления")
        } else {
            EngineCopyright.Text(text)
        }
    }.getOrElse { error ->
        EngineCopyright.Unavailable(
            "не удалось получить уведомление ядра: ${error.message ?: "неизвестная ошибка"}",
        )
    }
}
