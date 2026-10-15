package com.impossi8le.vpnapp.domain.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Модель события телеметрии и её сериализация.
 *
 * Граница приватности задана ТИПОМ, а не дисциплиной: в [TelemetryEvent] нет
 * полей для токена, ключа или `device_nonce` — их некуда положить. Это тот же
 * приём, что в `DiagnosticsInput`: границу видно по списку полей, и тест может
 * доказать отсутствие утечки, а не полагаться на «вроде не передавали».
 */
class ReportModelTest {

    private val json = Json { encodeDefaults = false }

    @Test
    fun `сериализованное событие не содержит секретов`() {
        val event = TelemetryEvent(
            eventId = "0f9c",
            type = EventType.StateChange,
            ts = "2026-10-13T09:20:11Z",
            state = "connected",
            configId = "LVclient193",
            configName = "Нидерланды",
            server = "192.0.2.10:1194",
            durationMs = 4210,
            code = null,
        )
        val text = json.encodeToString(TelemetryEvent.serializer(), event)

        assertFalse(text.contains("Bearer"), "заголовку авторизации не место в событии")
        assertFalse(text.contains("PRIVATE KEY"))
        assertTrue(text.contains("LVclient193"), "id подключения — часть диагностики по решению владельца")
        assertTrue(text.contains("192.0.2.10:1194"), "адрес сервера тоже входит в авто-метрики")
    }

    @Test
    fun `eventId нужен для идемпотентности и всегда есть`() {
        val event = TelemetryEvent(
            eventId = "abc-123",
            type = EventType.Error,
            ts = "2026-10-13T09:20:11Z",
            state = "failed",
            configId = null,
            configName = null,
            server = null,
            durationMs = null,
            code = "tun_prop_error",
        )
        val text = json.encodeToString(TelemetryEvent.serializer(), event)

        assertTrue(text.contains("\"event_id\":\"abc-123\""))
        assertTrue(text.contains("\"code\":\"tun_prop_error\""))
    }

    @Test
    fun `поля имён в JSON — snake_case по контракту`() {
        val event = TelemetryEvent(
            eventId = "e1", type = EventType.ConnectAttempt, ts = "t", state = null,
            configId = null, configName = null, server = null, durationMs = 100, code = null,
        )
        val obj = Json.parseToJsonElement(
            json.encodeToString(TelemetryEvent.serializer(), event),
        ).jsonObject

        assertTrue(obj.containsKey("event_id"))
        assertTrue(obj.containsKey("duration_ms"))
        assertEquals("connect_attempt", obj["type"]!!.jsonPrimitive.content)
    }
}
