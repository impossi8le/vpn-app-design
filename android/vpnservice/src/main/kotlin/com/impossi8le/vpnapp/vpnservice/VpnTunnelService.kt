package com.impossi8le.vpnapp.vpnservice

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.impossi8le.vpnapp.vpnengine.OpenVpn3Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Сервис туннеля.
 *
 * Здесь живёт `VpnService` и мост к ядру OpenVPN 3. Разделение с `core:tunnel`
 * как на iOS: приложение только просит поднять туннель, поднимает его сервис.
 *
 * **Про `foregroundServiceType="specialUse"`.** Тип `dataSync` для VPN
 * запрещён: Android 14 обрывает такие сервисы через шесть часов. VPN должен жить
 * дольше, поэтому в манифесте стоит `specialUse` с пояснением назначения.
 *
 * **Про `setBlocking`.** Он блокирует трафик, только пока жив интерфейс. Смерть
 * сервиса снимает блокировку, и трафик уходит открытым — это НЕ killswitch,
 * вопреки распространённому мнению. Единственная защита, переживающая такая
 * смерть, — Always-on VPN, и включает её пользователь в системных настройках.
 */
class VpnTunnelService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: OpenVpn3Session? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val profileText = intent.getStringExtra(EXTRA_PROFILE)
                if (profileText.isNullOrBlank()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startTunnel(profileText)
            }

            ACTION_DISCONNECT -> stopTunnel()
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(profileText: String) {
        if (session != null) return

        val builder = Builder()
            .setSession(SESSION_NAME)
            // Блокировка на время жизни интерфейса. Не killswitch: см. doc-класс.
            .setBlocking(true)
            .setMtu(DEFAULT_MTU)

        val tun = VpnServiceTunBuilder(this, builder)
        val newSession = OpenVpn3Session(tun)

        session = newSession
        scope.launch {
            newSession.start(profileText)
        }
    }

    private fun stopTunnel() {
        session?.stop()
        session = null
    }

    override fun onRevoke() {
        // Система отозвала разрешение VPN: останавливаемся немедленно и честно.
        // Здесь нельзя пытаться «дожать» блокировку — интерфейса уже не будет.
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        scope.cancel()
        super.onDestroy()
    }

    /** Дескриптор интерфейса, созданный мостом. Закрывается при остановке. */
    internal var tunDescriptor: ParcelFileDescriptor? = null

    companion object {
        const val ACTION_CONNECT = "com.impossi8le.vpnapp.vpnservice.CONNECT"
        const val ACTION_DISCONNECT = "com.impossi8le.vpnapp.vpnservice.DISCONNECT"
        const val EXTRA_PROFILE = "profile"

        private const val SESSION_NAME = "VPN"
        private const val DEFAULT_MTU = 1400
    }
}
