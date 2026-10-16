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

    @Test
    fun `обрыв попытки не приклеивает простой к следующему подключению`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.Disconnected)
        var now = 1_000L
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            nowMs = { now },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        advanceUntilIdle()

        // Попытка стартует и тут же обрывается в «отключено» — как в проде
        // (connecting 19:07 → disconnected 19:08).
        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()
        now = 2_000L
        tunnel.emit(ConnectionStatus.Disconnected)
        advanceUntilIdle()

        // Долгий простой: если таймер не сброшен, именно это время уедет в метрику.
        now = 500_000L
        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()
        now = 501_000L
        tunnel.emit(ConnectionStatus.VerifyingProtection)
        advanceUntilIdle()

        val attempt = events(queue).last { it["type"] == "connect_attempt" }
        assertEquals(
            "1000",
            attempt["duration_ms"],
            "подключение заняло секунду, а не время простоя: оборванная попытка обязана сбросить таймер",
        )
    }

    @Test
    fun `оборванная попытка записывается отдельным исходом, а не как подключённая`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.Disconnected)
        var now = 1_000L
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            nowMs = { now },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        advanceUntilIdle()

        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()
        now = 1_500L
        tunnel.emit(ConnectionStatus.Disconnected)
        advanceUntilIdle()

        val attempt = events(queue).single { it["type"] == "connect_attempt" }
        assertEquals(TunnelTelemetryWatcher.STATE_ABANDONED, attempt["state"])
        assertEquals("500", attempt["duration_ms"])
    }

    @Test
    fun `обычная остановка без попытки не пишет connect_attempt`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.VerifyingProtection)
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        advanceUntilIdle()

        // Попытки не было — сброс таймера на «отключено» не должен ничего писать.
        tunnel.emit(ConnectionStatus.Disconnected)
        advanceUntilIdle()

        assertTrue(
            events(queue).none { it["type"] == "connect_attempt" },
            "нет попытки — нет события: иначе статистика наполнилась бы пустыми исходами",
        )
    }

    @Test
    fun `попытка подключения несёт контекст, а не только длительность`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.Disconnected)
        var now = 1_000L
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            context = { TelemetryContext("c1", "Нидерланды", "192.0.2.10:1194") },
            nowMs = { now },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        advanceUntilIdle()

        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()
        now = 2_000L
        tunnel.emit(ConnectionStatus.VerifyingProtection)
        advanceUntilIdle()

        val attempt = events(queue).single { it["type"] == "connect_attempt" }
        assertEquals("c1", attempt["config_id"])
        assertEquals("Нидерланды", attempt["config_name"])
        assertEquals("192.0.2.10:1194", attempt["server"])
    }

    @Test
    fun `обрыв пишет error, а не молчит`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.Disconnected)
        var now = 1_000L
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            nowMs = { now },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        advanceUntilIdle()

        // Попытка не поднялась и оборвалась сама — как 34 из 47 в проде.
        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()
        now = 11_000L
        tunnel.emit(ConnectionStatus.Disconnected)
        advanceUntilIdle()

        val error = events(queue).single { it["type"] == "error" }
        assertEquals(
            TunnelTelemetryWatcher.ERROR_TUNNEL_DROPPED,
            error["code"],
            "обрыв обязан оставить запись в канале error: иначе «не подключается» нечем подтвердить",
        )
        // Исход попытки тоже записан — со своим кодом, не как «подключено».
        val attempt = events(queue).single { it["type"] == "connect_attempt" }
        assertEquals(TunnelTelemetryWatcher.STATE_ABANDONED, attempt["state"])
        assertEquals("10000", attempt["duration_ms"])
    }

    @Test
    fun `отмена пользователем не считается сбоем`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.Disconnected)
        var now = 1_000L
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            nowMs = { now },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        advanceUntilIdle()

        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()
        now = 4_000L
        // Пользователь нажал «Отменить» — экран помечает останов перед disconnect().
        watcher.noteUserDisconnect()
        tunnel.emit(ConnectionStatus.Disconnected)
        advanceUntilIdle()

        val attempt = events(queue).single { it["type"] == "connect_attempt" }
        assertEquals(
            TunnelTelemetryWatcher.STATE_CANCELLED,
            attempt["state"],
            "отмена — не сбой: исход обязан отличаться от оборванной попытки",
        )
        assertTrue(
            events(queue).none { it["type"] == "error" },
            "отмена не ошибка: писать её в канал error значит завышать счёт проблем сервера",
        )
    }

    @Test
    fun `отмена гасит флаг — следующий обрыв снова сбой`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.Disconnected)
        var now = 1_000L
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            nowMs = { now },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        advanceUntilIdle()

        // Отмена…
        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()
        watcher.noteUserDisconnect()
        now = 2_000L
        tunnel.emit(ConnectionStatus.Disconnected)
        advanceUntilIdle()

        // …затем НОВАЯ попытка, оборвавшаяся сама. Флаг не должен пережить
        // свою смену и пометить этот разрыв отменой.
        now = 3_000L
        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()
        now = 13_000L
        tunnel.emit(ConnectionStatus.Disconnected)
        advanceUntilIdle()

        val outcomes = events(queue)
            .filter { it["type"] == "connect_attempt" }
            .map { it["state"] }
        assertEquals(
            listOf(TunnelTelemetryWatcher.STATE_CANCELLED, TunnelTelemetryWatcher.STATE_ABANDONED),
            outcomes,
        )
        assertEquals(1, events(queue).count { it["type"] == "error" })
    }

    @Test
    fun `смена конфига помечает disconnected прежним сервером`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.Disconnected)
        // Лямбда возвращает ТЕКУЩИЙ выбор — как `context()` в проде. Меняем его
        // между событиями, воспроизводя переключение конфига: профиль нового
        // пишется до `disconnect()`, поэтому к разрыву мета уже новая.
        var ctx = TelemetryContext("AUclient181", "Австралия", "192.0.2.1:1194")
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            context = { ctx },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        advanceUntilIdle()

        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()
        tunnel.emit(ConnectionStatus.VerifyingProtection)
        advanceUntilIdle()

        // Пользователь переключил конфиг: мета/выбор указывают на новый сервер,
        // затем старый туннель опускается и поднимается новый.
        ctx = TelemetryContext("GEclient125", "Германия", "192.0.2.2:1194")
        watcher.noteUserDisconnect()
        tunnel.emit(ConnectionStatus.Disconnected)
        advanceUntilIdle()
        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()
        tunnel.emit(ConnectionStatus.VerifyingProtection)
        advanceUntilIdle()

        val disconnected = events(queue)
            .filter { it["type"] == "state_change" && it["state"] == "disconnected" }
            .single()
        assertEquals(
            "AUclient181",
            disconnected["config_id"],
            "отключение СТАРОГО туннеля обязано нести старый id, а не новый (issue #25)",
        )
        assertEquals("192.0.2.1:1194", disconnected["server"])

        // А подключение нового — своим, новым конфигом.
        val connected = events(queue).filter { it["state"] == "connecting" }.last()
        assertEquals("GEclient125", connected["config_id"])
    }

    @Test
    fun `ошибка уходит с контекстом сервера`() = runTest {
        val queue = FakeQueue()
        val tunnel = FakeTunnelControlling(ConnectionStatus.Disconnected)
        val watcher = TunnelTelemetryWatcher(
            tunnel = tunnel,
            recorder = TelemetryRecorder(queue, clock = { "t" }, idFactory = { "id" }),
            context = { TelemetryContext("c1", "Нидерланды", "192.0.2.10:1194") },
            nowMs = { 1_000L },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        watcher.start(TestScope(testScheduler))
        advanceUntilIdle()

        tunnel.emit(ConnectionStatus.Connecting)
        advanceUntilIdle()
        tunnel.emit(ConnectionStatus.Failed("нет сети"))
        advanceUntilIdle()

        val error = events(queue).single { it["type"] == "error" }
        assertEquals("c1", error["config_id"])
        assertEquals("192.0.2.10:1194", error["server"])
    }
}
