package com.impossi8le.vpnapp.tunnel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Слушает сообщения сервиса о состоянии туннеля.
 *
 * Сервис живёт в своём процессе и не может напрямую менять состояние экрана.
 * Состояние передаётся намерениями, приёмник переводит его в наш тип через
 * `SystemState.toConnectionStatus()` — то есть системное состояние НИКОГДА не
 * попадает в домен напрямую и не может стать зелёным по недосмотру.
 *
 * Отдельно обрабатывается смена сети: это не новое состояние туннеля, а событие,
 * обесценивающее сделанный замер. Через `status` его не передать — `StateFlow`
 * схлопывает одинаковые значения, — поэтому контроллер получает отдельный сигнал.
 */
class TunnelStatusReceiver(
    private val controller: AppTunnelController,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STATE -> {
                val state = intent.getStringExtra(EXTRA_STATE)
                    ?.let { runCatching { SystemState.valueOf(it) }.getOrNull() }
                    ?: return
                controller.onServiceState(state)
            }

            ACTION_NETWORK_CHANGED -> controller.onNetworkChanged()
        }
    }

    companion object {
        const val ACTION_STATE = "com.impossi8le.vpnapp.tunnel.STATE"
        const val ACTION_NETWORK_CHANGED = "com.impossi8le.vpnapp.tunnel.NETWORK_CHANGED"
        const val EXTRA_STATE = "state"
    }
}
