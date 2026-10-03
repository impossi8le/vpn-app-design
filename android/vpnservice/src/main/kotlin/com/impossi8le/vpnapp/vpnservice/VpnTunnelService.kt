package com.impossi8le.vpnapp.vpnservice

import android.content.Intent
import android.net.VpnService

/**
 * Заглушка каркаса. Реализация — поток WA6.
 *
 * Объявлен в манифесте, поэтому класс обязан существовать: иначе приложение
 * упадёт в момент старта сервиса, а lint пометит манифест как несогласованный.
 *
 * Что здесь появится (§4.8 архитектуры):
 *  - foreground-сервис с нотификацией: без него система убивает VPN начиная с Android 8;
 *  - `Builder` с полным перехватом IPv4, закрытием IPv6 маршрутом `::/0` и DNS из профиля;
 *  - JNI-мост к `libopenvpn.so` из vendor:ics-openvpn.
 *
 * Чего здесь НЕ будет: парсинга `.ovpn` (он внутри ics-openvpn), создания
 * `VpnService`-менеджера (это app-side, `core:tunnel`) и решения о зелёном статусе —
 * его принимает только `ProtectionGate` (§6).
 */
class VpnTunnelService : VpnService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Заглушка: сервис не поднимает туннель и не держит foreground.
        return START_NOT_STICKY
    }
}
