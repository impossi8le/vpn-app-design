package com.impossi8le.vpnapp.tunnel

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Android-чтение факта «VPN-туннель есть» через [ConnectivityManager].
 *
 * Факт берётся у системы, а не у движка: пока туннель жив, активная сеть
 * устройства И ЕСТЬ VPN-сеть, и у неё выставлен транспорт `VPN` — на это же
 * опирается `AndroidProtectionProbe`. Пропажу интерфейса система отражает
 * сама — исчезает VPN-сеть из списка, и это видно здесь, даже если ядро
 * замолчало и не прислало `Disconnected`. В разобранном случае на телефоне
 * `dumpsys connectivity` не показывал НИ ОДНОЙ сети с `Transports: VPN`, а весь
 * трафик шёл через `wlan0` (см.
 * `docs/testing/2026-10-06-connected-without-tunnel.md`).
 *
 * **Почему активная сеть, а не `allNetworks` с `TRANSPORT_VPN`.** Перебирать
 * все сети значило бы признать туннелем любую постороннюю VPN-сеть на телефоне —
 * чужое приложение подняло свой туннель, а мы показали бы «Подключено».
 * Активная сеть — та, через которую реально идёт трафик: если она VPN, это наш
 * туннель; если она `wlan0`/`cellular` — туннеля над трафиком нет.
 *
 * **ГРАНИЦА ЧЕСТНОСТИ: [TunnelPresence.ABSENT] — только по положительному
 * признаку.** «Туннеля нет» возвращается ровно тогда, когда активная сеть ЕСТЬ и
 * она заведомо не VPN: это прямое доказательство отсутствия, и оно не может
 * задеть исправный туннель (пока туннель поднят, активная сеть — VPN). Всё, что
 * не удалось прочитать (нет активной сети, `null`-возможности, бросивший вызов
 * системный метод), — [TunnelPresence.UNKNOWN]: неизвестность не доказательство
 * отсутствия, и по ней рубить исправный туннель нельзя. Иначе один сбойный такт
 * чтения переводил бы здоровое соединение в «отключено».
 *
 * Разрешение `ACCESS_NETWORK_STATE` объявлено в манифесте, новый не нужен.
 */
class AndroidTunnelPresence(
    private val connectivity: ConnectivityManager,
) : TunnelPresenceProbe {

    override fun read(): TunnelPresence {
        try {
            val active = connectivity.activeNetwork ?: return TunnelPresence.UNKNOWN
            val caps = connectivity.getNetworkCapabilities(active) ?: return TunnelPresence.UNKNOWN
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                // Активная сеть — не VPN, и она реально несёт трафик: значит, над
                // трафиком туннеля нет. Ровно тот случай, когда `ip route get
                // 8.8.8.8` показал `dev wlan0`.
                return TunnelPresence.ABSENT
            }
            // Сеть помечена VPN: подтверждаем факт именем интерфейса. Пустое имя
            // или нечитаемые свойства — не «нет», а «не удалось подтвердить»:
            // положительного доказательства отсутствия здесь нет.
            val name = connectivity.getLinkProperties(active)?.interfaceName
            return if (name.isNullOrBlank()) TunnelPresence.UNKNOWN else TunnelPresence.PRESENT
        } catch (_: Exception) {
            // Системный вызов бросил — спросить не удалось. Это не доказательство
            // отсутствия туннеля: рапортовать «нет» здесь значило бы рубить
            // исправный туннель из-за случайного сбоя.
            return TunnelPresence.UNKNOWN
        }
    }
}
