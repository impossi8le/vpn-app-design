package com.impossi8le.vpnapp.tunnel

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Сторож существования туннеля.
 *
 * Всё на виртуальном времени: тест не должен ждать реальные секунды, чтобы
 * убедиться, что опрос идёт раз в интервал.
 *
 * Обязательные приёмы: `runCurrent()` после `restart` (задание ещё не
 * подписалось) и `stop()`/`advanceUntilIdle()` в конце — иначе `runTest` найдёт
 * незавершённое задание и упадёт.
 */
class TunnelPresenceWatcherTest {

    /** Фейк-проба: отдаёт заготовленные ответы по очереди, последний — вечно. */
    private class FakeProbe(answers: List<TunnelPresence>) : TunnelPresenceProbe {
        private val queue = ArrayDeque(answers)
        var reads = 0
            private set

        override fun read(): TunnelPresence {
            reads++
            if (queue.size > 1) return queue.removeFirst()
            return queue.firstOrNull() ?: TunnelPresence.UNKNOWN
        }
    }

    @Test
    fun `исчезнувший туннель переводит в отключено`() = runTest {
        val probe = FakeProbe(listOf(TunnelPresence.PRESENT, TunnelPresence.ABSENT))
        val watcher = TunnelPresenceWatcher(probe, interval = 5.seconds)
        var last: ConnectionStatus? = null

        watcher.restart(
            this,
            current = { ConnectionStatus.VerifyingProtection },
            onTunnelLost = { last = it },
        )
        runCurrent()

        advanceTimeBy(6.seconds)
        runCurrent()
        assertTrue(last == null, "пока туннель на месте, гасить нечего")

        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(ConnectionStatus.Disconnected, last, "пропажа туннеля обязана погасить «подключено»")

        watcher.stop()
        advanceUntilIdle()
    }

    @Test
    fun `неизвестность не гасит и не мешает следующей проверке`() = runTest {
        // PRESENT → UNKNOWN → ABSENT: сбой опроса не рубит туннель, но и не
        // останавливает наблюдение — факт подтверждается на следующем шаге.
        val probe = FakeProbe(
            listOf(TunnelPresence.PRESENT, TunnelPresence.UNKNOWN, TunnelPresence.ABSENT),
        )
        val watcher = TunnelPresenceWatcher(probe, interval = 5.seconds)
        var last: ConnectionStatus? = null

        watcher.restart(
            this,
            current = { ConnectionStatus.VerifyingProtection },
            onTunnelLost = { last = it },
        )
        runCurrent()

        advanceTimeBy(6.seconds)
        runCurrent()
        assertTrue(last == null, "неизвестность — не доказательство отсутствия")

        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(ConnectionStatus.Disconnected, last)

        watcher.stop()
        advanceUntilIdle()
    }

    @Test
    fun `в покое сторож не опрашивает вовсе`() = runTest {
        val probe = FakeProbe(listOf(TunnelPresence.ABSENT))
        val watcher = TunnelPresenceWatcher(probe, interval = 5.seconds)

        watcher.restart(this, current = { ConnectionStatus.Disconnected }, onTunnelLost = { })
        runCurrent()

        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(0, probe.reads, "нечего сторожить — нечего и опрашивать")

        watcher.stop()
        advanceUntilIdle()
    }

    @Test
    fun `подключение не стережётся — интерфейса там ещё нет`() = runTest {
        val probe = FakeProbe(listOf(TunnelPresence.ABSENT))
        val watcher = TunnelPresenceWatcher(probe, interval = 5.seconds)

        watcher.restart(this, current = { ConnectionStatus.Connecting }, onTunnelLost = { })
        runCurrent()

        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(0, probe.reads)

        watcher.stop()
        advanceUntilIdle()
    }

    @Test
    fun `повторный restart не удваивает опрос`() = runTest {
        val probe = FakeProbe(listOf(TunnelPresence.PRESENT))
        val watcher = TunnelPresenceWatcher(probe, interval = 5.seconds)

        watcher.restart(this, current = { ConnectionStatus.VerifyingProtection }, onTunnelLost = { })
        runCurrent()
        watcher.restart(this, current = { ConnectionStatus.VerifyingProtection }, onTunnelLost = { })
        runCurrent()

        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(1, probe.reads, "после повторного restart должен остаться ровно один цикл")

        watcher.stop()
        advanceUntilIdle()
    }

    @Test
    fun `stop прекращает опрос`() = runTest {
        val probe = FakeProbe(listOf(TunnelPresence.PRESENT))
        val watcher = TunnelPresenceWatcher(probe, interval = 5.seconds)

        watcher.restart(this, current = { ConnectionStatus.VerifyingProtection }, onTunnelLost = { })
        runCurrent()
        watcher.stop()

        advanceTimeBy(60.seconds)
        runCurrent()
        assertEquals(0, probe.reads, "после остановки сторож не должен трогать систему")

        advanceUntilIdle()
    }
}
