package com.impossi8le.vpnapp.feature.home

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionGate
import com.impossi8le.vpnapp.domain.protection.ReverificationPolicy
import com.impossi8le.vpnapp.testsupport.FactProbe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Наблюдатель отвечает за то, чтобы подтверждение не жило дольше правды.
 *
 * Всё в виртуальном времени: тест не должен ждать минуту, чтобы убедиться, что
 * перепроверка идёт раз в минуту.
 *
 * Обязательные приёмы в каждом тесте:
 *  - `runCurrent()` после `start` — запущенные коллекторы ещё не подписались,
 *    и эмиссия в поток смены сети ушла бы в пустоту;
 *  - `watcher.stop()` в конце — иначе `runTest` дожидается незакрытых заданий
 *    и падает с UncompletedCoroutinesError.
 */
class ProtectionWatcherTest {

    @Test
    fun `смена сети вызывает немедленную перепроверку и сбрасывает прежнее подтверждение`() = runTest {
        val probe = FactProbe(Triple(true, true, true))
        val watcher = ProtectionWatcher(ProtectionGate(probe))
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        val verdicts = mutableListOf<ConnectionStatus>()
        var invalidations = 0
        watcher.start(this, changes, onVerdict = { verdicts += it }, onInvalidated = { invalidations++ })
        runCurrent()

        changes.tryEmit(Unit)
        runCurrent()

        assertEquals(1, verdicts.size, "смена сети обязана вызвать замер сразу")
        assertEquals(1, invalidations, "прежнее подтверждение сбрасывается до вердикта")

        watcher.stop()
        advanceUntilIdle()
    }

    @Test
    fun `после смены сети и провалившегося замера зелёное недопустимо`() = runTest {
        val probe = FactProbe(Triple(true, true, true))
        val watcher = ProtectionWatcher(ProtectionGate(probe))
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        val verdicts = mutableListOf<ConnectionStatus>()
        watcher.start(this, changes, onVerdict = { verdicts += it }, onInvalidated = {})
        runCurrent()

        changes.tryEmit(Unit)
        runCurrent()
        assertTrue(verdicts.last().isProtected)

        // Сеть уехала: маршруты больше не закрывают IPv6.
        probe.withFacts(ipv4 = true, ipv6 = false, dns = true)
        changes.tryEmit(Unit)
        runCurrent()

        assertFalse(verdicts.last().isProtected, "зелёное не должно пережить смену сети")

        watcher.stop()
        advanceUntilIdle()
    }

    @Test
    fun `перепроверка повторяется по таймеру`() = runTest {
        val watcher = ProtectionWatcher(
            ProtectionGate(FactProbe(Triple(true, true, true))),
            ReverificationPolicy(interval = 30.seconds, onNetworkChange = false),
        )
        val verdicts = mutableListOf<ConnectionStatus>()
        watcher.start(this, MutableSharedFlow(), onVerdict = { verdicts += it }, onInvalidated = {})
        runCurrent()

        advanceTimeBy(31.seconds)
        runCurrent()
        assertEquals(1, verdicts.size, "через интервал замер обязан состояться")

        advanceTimeBy(30.seconds)
        runCurrent()
        assertEquals(2, verdicts.size, "и повторяться")

        watcher.stop()
        advanceUntilIdle()
    }

    @Test
    fun `до истечения интервала замер не делается`() = runTest {
        val watcher = ProtectionWatcher(
            ProtectionGate(FactProbe(Triple(true, true, true))),
            ReverificationPolicy(interval = 60.seconds, onNetworkChange = false),
        )
        val verdicts = mutableListOf<ConnectionStatus>()
        watcher.start(this, MutableSharedFlow(), onVerdict = { verdicts += it }, onInvalidated = {})
        runCurrent()

        advanceTimeBy(50.seconds)
        runCurrent()
        assertTrue(verdicts.isEmpty(), "замер не должен идти раньше срока")

        watcher.stop()
        advanceUntilIdle()
    }

    @Test
    fun `после остановки не остаётся живых заданий`() = runTest {
        // Если stop() не отменяет подписку на смену сети, повторный start
        // добавляет вторую подписку, и одно событие вызывает два замера.
        val watcher = ProtectionWatcher(ProtectionGate(FactProbe(Triple(true, true, true))))
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        watcher.start(this, changes, onVerdict = { }, onInvalidated = {})
        runCurrent()
        watcher.stop()

        // advanceUntilIdle не должен найти незавершённых заданий: если найдёт,
        // runTest упадёт сам — то есть тест и есть проверка.
        advanceUntilIdle()
    }

    @Test
    fun `повторный start не удваивает замеры`() = runTest {
        val watcher = ProtectionWatcher(ProtectionGate(FactProbe(Triple(true, true, true))))
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        val verdicts = mutableListOf<ConnectionStatus>()
        val onVerdict: (ConnectionStatus) -> Unit = { verdicts += it }
        watcher.start(this, changes, onVerdict, {})
        runCurrent()
        watcher.start(this, changes, onVerdict, {})
        runCurrent()

        changes.tryEmit(Unit)
        runCurrent()

        assertEquals(
            1,
            verdicts.size,
            "после повторного start должна остаться ровно одна подписка, иначе один разрыв сети = два замера",
        )

        watcher.stop()
        advanceUntilIdle()
    }

    @Test
    fun `слишком частый интервал отвергается`() {
        // Замер делает сетевые обращения: частый опрос сам создаёт нагрузку.
        assertThrows(IllegalArgumentException::class.java) {
            ReverificationPolicy(interval = 1.seconds)
        }
    }

    @Test
    fun `нулевой интервал отвергается`() {
        assertThrows(IllegalArgumentException::class.java) {
            ReverificationPolicy(interval = kotlin.time.Duration.ZERO)
        }
    }
}
