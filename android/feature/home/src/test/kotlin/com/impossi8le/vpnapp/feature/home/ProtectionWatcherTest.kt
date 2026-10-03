package com.impossi8le.vpnapp.feature.home

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionGate
import com.impossi8le.vpnapp.domain.protection.ReverificationPolicy
import com.impossi8le.vpnapp.testsupport.FactProbe
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
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
 * Проверяется в виртуальном времени: тест не должен ждать минуту, чтобы убедиться,
 * что перепроверка происходит раз в минуту.
 */
class ProtectionWatcherTest {

    private val scheduler = TestCoroutineScheduler()

    @Test
    fun `смена сети вызывает немедленную перепроверку`() = runTest(scheduler) {
        val probe = FactProbe(Triple(true, true, true))
        val watcher = ProtectionWatcher(ProtectionGate(probe))
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        val verdicts = mutableListOf<ConnectionStatus>()
        var invalidations = 0

        watcher.start(this, changes, onVerdict = { verdicts += it }, onInvalidated = { invalidations++ })
        advanceTimeBy(0)

        changes.tryEmit(Unit)
        advanceTimeBy(0)

        assertEquals(1, verdicts.size, "смена сети обязана вызвать замер сразу")
        assertEquals(1, invalidations, "прежнее подтверждение сбрасывается до вердикта")
    }

    @Test
    fun `замер после смены сети отменяет прежнее подтверждение`() = runTest(scheduler) {
        val probe = FactProbe(Triple(true, true, true))
        val watcher = ProtectionWatcher(ProtectionGate(probe))
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        val verdicts = mutableListOf<ConnectionStatus>()
        watcher.start(this, changes, onVerdict = { verdicts += it }, onInvalidated = {})
        advanceTimeBy(0)

        changes.tryEmit(Unit)
        advanceTimeBy(0)
        assertTrue(verdicts.last().isProtected)

        // Сеть уехала: маршруты больше не закрывают IPv6.
        probe.withFacts(ipv4 = true, ipv6 = false, dns = true)
        changes.tryEmit(Unit)
        advanceTimeBy(0)

        assertFalse(
            verdicts.last().isProtected,
            "после смены сети и провалившегося замера зелёное недопустимо",
        )
    }

    @Test
    fun `перепроверка происходит по таймеру`() = runTest(scheduler) {
        val probe = FactProbe(Triple(true, true, true))
        val watcher = ProtectionWatcher(
            ProtectionGate(probe),
            ReverificationPolicy(interval = 30.seconds, onNetworkChange = false),
        )
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        val verdicts = mutableListOf<ConnectionStatus>()
        watcher.start(this, changes, onVerdict = { verdicts += it }, onInvalidated = {})

        advanceTimeBy(31.seconds)
        assertEquals(1, verdicts.size, "через интервал замер обязан состояться")

        advanceTimeBy(30.seconds)
        assertEquals(2, verdicts.size, "и повторяться")
    }

    @Test
    fun `до истечения интервала замер не делается`() = runTest(scheduler) {
        val probe = FactProbe(Triple(true, true, true))
        val watcher = ProtectionWatcher(
            ProtectionGate(probe),
            ReverificationPolicy(interval = 60.seconds, onNetworkChange = false),
        )

        val verdicts = mutableListOf<ConnectionStatus>()
        watcher.start(this, MutableSharedFlow(), onVerdict = { verdicts += it }, onInvalidated = {})

        advanceTimeBy(50.seconds)
        assertTrue(verdicts.isEmpty(), "замер не должен идти раньше срока")
    }

    @Test
    fun `таймер останавливается`() = runTest(scheduler) {
        val watcher = ProtectionWatcher(
            ProtectionGate(FactProbe(Triple(true, true, true))),
            ReverificationPolicy(interval = 10.seconds, onNetworkChange = false),
        )
        val verdicts = mutableListOf<ConnectionStatus>()
        watcher.start(this, MutableSharedFlow(), onVerdict = { verdicts += it }, onInvalidated = {})

        advanceTimeBy(11.seconds)
        assertEquals(1, verdicts.size)

        watcher.stop()
        advanceTimeBy(60.seconds)
        assertEquals(1, verdicts.size, "после остановки замеров быть не должно")
    }

    @Test
    fun `повторный start не удваивает замеры`() = runTest(scheduler) {
        val watcher = ProtectionWatcher(
            ProtectionGate(FactProbe(Triple(true, true, true))),
            ReverificationPolicy(interval = 10.seconds, onNetworkChange = false),
        )
        val verdicts = mutableListOf<ConnectionStatus>()
        // Иначе два таймера удвоят сетевые обращения и счётчики.
        watcher.start(this, MutableSharedFlow(), onVerdict = { verdicts += it }, onInvalidated = {})
        watcher.start(this, MutableSharedFlow(), onVerdict = { verdicts += it }, onInvalidated = {})

        advanceTimeBy(11.seconds)
        assertEquals(1, verdicts.size, "должен остаться ровно один таймер")
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
