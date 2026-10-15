package com.impossi8le.vpnapp.domain.diagnostics

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Тип события телеметрии.
 *
 * Три вида, а не общий «лог»: по `state_change` видно, где рвётся туннель, по
 * `error` — с каким кодом, по `connect_attempt` — сколько занимает подключение.
 */
@Serializable
enum class EventType {
    @SerialName("state_change") StateChange,
    @SerialName("error") Error,
    @SerialName("connect_attempt") ConnectAttempt,
}

/**
 * Одно событие авто-метрики.
 *
 * **Граница приватности.** Полей для секретов здесь нет: токен, ключ и
 * `device_nonce` физически некуда положить. `configId`, `configName` и `server`
 * включены осознанно (решение владельца, 2026-10-13).
 */
@Serializable
data class TelemetryEvent(
    @SerialName("event_id") val eventId: String,
    @SerialName("type") val type: EventType,
    @SerialName("ts") val ts: String,
    @SerialName("state") val state: String? = null,
    @SerialName("config_id") val configId: String? = null,
    @SerialName("config_name") val configName: String? = null,
    @SerialName("server") val server: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
    @SerialName("code") val code: String? = null,
)
