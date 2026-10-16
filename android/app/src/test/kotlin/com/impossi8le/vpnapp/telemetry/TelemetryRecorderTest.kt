package com.impossi8le.vpnapp.telemetry

import com.impossi8le.vpnapp.domain.diagnostics.ReportQueue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Запись событий в очередь.
 *
 * Проверяется то, из-за отсутствия чего авто-контур был мёртв: события реально
 * попадают в очередь, несут `event_id` (без него идемпотентность невозможна) и
 * `ts` приходит извне, а не из часов, — иначе тест был бы недетерминирован.
 */
class TelemetryRecorderTest {

    private class FakeQueue : ReportQueue {
        val items = mutableListOf<String>()
        override suspend fun enqueue(json: String) {
            items += json
        }

        override suspend fun peek(): List<String> = items
        override suspend fun remove(ids: List<String>) {
            items.removeAll { line -> ids.any { line.contains(it) } }
        }

        override suspend fun size(): Int = items.size
        override suspend fun clear() {
            items.clear()
        }
    }

    @Test
    fun `изменение состояния попадает в очередь с event_id`() = runBlocking {
        val queue = FakeQueue()
        val recorder = TelemetryRecorder(
            queue = queue,
            clock = { "2026-10-15T10:00:00Z" },
            idFactory = { "fixed-id" },
        )

        recorder.stateChanged(
            state = "connected",
            configId = "c1",
            configName = "Нидерланды",
            server = "192.0.2.10:1194",
        )

        assertEquals(1, queue.items.size)
        val obj = Json.parseToJsonElement(queue.items.first()).jsonObject
        assertEquals("fixed-id", obj["event_id"]!!.jsonPrimitive.content)
        assertEquals("state_change", obj["type"]!!.jsonPrimitive.content)
        assertEquals("2026-10-15T10:00:00Z", obj["ts"]!!.jsonPrimitive.content)
        assertEquals("connected", obj["state"]!!.jsonPrimitive.content)
        assertEquals("c1", obj["config_id"]!!.jsonPrimitive.content)
        assertEquals("192.0.2.10:1194", obj["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ошибка попадает в очередь с кодом`() = runBlocking {
        val queue = FakeQueue()
        val recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "e1" })

        recorder.error(code = "tun_prop_error", state = "failed")

        val obj = Json.parseToJsonElement(queue.items.first()).jsonObject
        assertEquals("error", obj["type"]!!.jsonPrimitive.content)
        assertEquals("tun_prop_error", obj["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `попытка подключения несёт длительность`() = runBlocking {
        val queue = FakeQueue()
        val recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "a1" })

        recorder.connectAttempt(state = "connected", durationMs = 4210)

        val obj = Json.parseToJsonElement(queue.items.first()).jsonObject
        assertEquals("connect_attempt", obj["type"]!!.jsonPrimitive.content)
        assertEquals(4210, obj["duration_ms"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `попытка подключения несёт контекст`() = runBlocking {
        val queue = FakeQueue()
        val recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "a1" })

        recorder.connectAttempt(
            state = "connected",
            durationMs = 4210,
            configId = "c1",
            configName = "Нидерланды",
            server = "192.0.2.10:1194",
        )

        val obj = Json.parseToJsonElement(queue.items.first()).jsonObject
        assertEquals("c1", obj["config_id"]!!.jsonPrimitive.content)
        assertEquals("Нидерланды", obj["config_name"]!!.jsonPrimitive.content)
        assertEquals("192.0.2.10:1194", obj["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ошибка несёт контекст`() = runBlocking {
        val queue = FakeQueue()
        val recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "e1" })

        recorder.error(
            code = "tunnel_failed",
            state = "failed",
            configId = "c1",
            configName = "Нидерланды",
            server = "192.0.2.10:1194",
        )

        val obj = Json.parseToJsonElement(queue.items.first()).jsonObject
        assertEquals("c1", obj["config_id"]!!.jsonPrimitive.content)
        assertEquals("Нидерланды", obj["config_name"]!!.jsonPrimitive.content)
        assertEquals("192.0.2.10:1194", obj["server"]!!.jsonPrimitive.content)
    }
}
