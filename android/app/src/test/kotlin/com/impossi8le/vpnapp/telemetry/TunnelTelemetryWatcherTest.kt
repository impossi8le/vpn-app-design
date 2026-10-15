package com.impossi8le.vpnapp.telemetry

import com.impossi8le.vpnapp.domain.diagnostics.ReportQueue
import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionFailure
import com.impossi8le.vpnapp.domain.protection.ProtectionVerdict
import com.impossi8le.vpnapp.testsupport.FakeTunnelControlling
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Подписка на состояние туннеля, наполняющая очередь метрик.
 *
 * Проверяется именно то, из-за отсутствия чего авто-контур был мёртв: очередь
 * перестаёт быть пустой, а событие рождается из СМЕНЫ состояния. Отдельно
 * закреплено, что подписка не пишет на текущее значение потока, — иначе поворот
 * экрана засорял бы очередь одинаковыми «подключено».
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelTelemetryWatcherTest {

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

    private fun events(queue: FakeQueue): List<Map<String, String>> =
        queue.items.map { line ->
            Json.parseToJsonElement(line).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content }
        }

    private fun types(queue: FakeQueue): List<String> = events(queue).map { it["type"]!! }

    @Test
    fun `смена состояния попадает в очередь`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.Disconnected)
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        // Коллектор стартует асинхронно; без этого шага эмит пришёлся бы на
        // момент, когда подписка ещё не подхватила поток.
        advanceUntilIdle()

        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()

        assertEquals(listOf("state_change"), types(queue))
    }

    @Test
    fun `текущее значение потока не пишется как событие`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.VerifyingProtection)
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        advanceUntilIdle()

        assertTrue(queue.items.isEmpty(), "подписка — не изменение: иначе поворот экрана слал бы событие")
    }

    @Test
    fun `ошибка туннеля уходит с кодом и попытка — с длительностью`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.Disconnected)
        var now = 1000L
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            nowMs = { now },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        // Коллектор стартует асинхронно; без этого шага эмит пришёлся бы на
        // момент, когда подписка ещё не подхватила поток.
        advanceUntilIdle()

        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()
        now = 5210L
        tunnel.emit(ConnectionStatus.VerifyingProtection)
        advanceUntilIdle()

        assertEquals(listOf("state_change", "state_change", "connect_attempt"), types(queue))
        val attempt = events(queue).last()
        assertEquals("4210", attempt["duration_ms"])
    }

    @Test
    fun `отказ туннеля даёт error с машинным кодом и попытку`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.Disconnected)
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            nowMs = { 1000L },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        // Коллектор стартует асинхронно; без этого шага эмит пришёлся бы на
        // момент, когда подписка ещё не подхватила поток.
        advanceUntilIdle()

        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()
        tunnel.emit(ConnectionStatus.Failed("нет сети"))
        advanceUntilIdle()

        assertEquals(listOf("state_change", "state_change", "error", "connect_attempt"), types(queue))
        val error = Json.parseToJsonElement(queue.items[2]).jsonObject
        assertEquals(TunnelTelemetryWatcher.ERROR_TUNNEL_FAILED, error["code"]!!.jsonPrimitive.content)
        assertEquals("failed", error["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `контекст подключения едет вместе с событием`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.Disconnected)
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            context = { TelemetryContext("c1", "Нидерланды", "192.0.2.10:1194") },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        // Коллектор стартует асинхронно; без этого шага эмит пришёлся бы на
        // момент, когда подписка ещё не подхватила поток.
        advanceUntilIdle()

        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()

        val event = Json.parseToJsonElement(queue.items.first()).jsonObject
        assertEquals("c1", event["config_id"]!!.jsonPrimitive.content)
        assertEquals("192.0.2.10:1194", event["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `защита и туннель различаются в поле state`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.Disconnected)
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        // Коллектор стартует асинхронно; без этого шага эмит пришёлся бы на
        // момент, когда подписка ещё не подхватила поток.
        advanceUntilIdle()

        tunnel.emit(ConnectionStatus.VerifyingProtection)
        // Между эмитами обязателен шаг: `status` — `StateFlow`, и без обработки
        // подписчиком промежуточное значение схлопнется с последующим. Это не
        // дефект, а свойство потока, и обходить его приходится именно так.
        advanceUntilIdle()
        tunnel.emit(
            ConnectionStatus.ProtectionFailed(
                ProtectionVerdict.Failed(ProtectionFailure.Inconclusive),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            listOf("connected", "protection_failed"),
            events(queue).filter { it["type"] == "state_change" }.map { it["state"]!! },
            "доказанное замером и неподтверждённое обязаны различаться: иначе граница §6 стирается в данных",
        )
    }
}
