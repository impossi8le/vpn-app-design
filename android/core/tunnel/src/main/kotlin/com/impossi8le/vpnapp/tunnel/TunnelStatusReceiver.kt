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
                // Число применённых обходов едет вместе с состоянием. Старый
                // сервис (или чужое намерение) мог его не прислать — тогда 0:
                // «обходов применено ноль» — честнее, чем показать число,
                // которого никто не подтверждал.
                val appliedBypass = intent.getIntExtra(EXTRA_BYPASS_APPLIED, 0)
                controller.onServiceState(state, appliedBypass)
            }

            ACTION_NETWORK_CHANGED -> controller.onNetworkChanged()
        }
    }

    companion object {
        const val ACTION_STATE = "com.impossi8le.vpnapp.tunnel.STATE"
        const val ACTION_NETWORK_CHANGED = "com.impossi8le.vpnapp.tunnel.NETWORK_CHANGED"
        const val EXTRA_STATE = "state"

        /**
         * Число применённых обходов в широковещании состояния.
         *
         * Строка обязана совпадать с `VpnTunnelService.EXTRA_BYPASS_APPLIED`
         * дословно: `:vpnservice` не зависит от `core:tunnel`, и строки контракта
         * продублированы на обеих сторонах (см. комментарий в сервисе).
         */
        const val EXTRA_BYPASS_APPLIED = "bypass_applied"
    }
}
