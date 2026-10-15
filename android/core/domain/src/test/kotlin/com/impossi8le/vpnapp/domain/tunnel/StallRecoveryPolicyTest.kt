package com.impossi8le.vpnapp.domain.tunnel

import com.impossi8le.vpnapp.domain.tunnel.StallRecoveryPolicy.MAX_ATTEMPTS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Правило автоперезапуска залипшего туннеля.
 *
 * Проверяется на JVM: настоящее залипание на телефоне случается редко и
 * непредсказуемо, поэтому «когда перезапускать» обязано быть данными, а не
 * логикой внутри наблюдателя.
 */
class StallRecoveryPolicyTest {

    @Test
    fun `живой туннель не перезапускается`() {
        val outcome = StallRecoveryPolicy.decide(
            TunnelHealth.Flowing,
            StallRecoveryState.Initial,
            nowMillis = 1_000_000,
        )

        assertEquals(StallRecovery.Wait, outcome.decision)
    }

    @Test
    fun `простой не перезапускается`() {
        // Самое важное: телефон в кармане выглядит как «нет трафика», но
        // перезапускать там нечего и вредно.
        val outcome = StallRecoveryPolicy.decide(
            TunnelHealth.Idle,
            StallRecoveryState.Initial,
            nowMillis = 1_000_000,
        )

        assertEquals(StallRecovery.Wait, outcome.decision)
    }

    @Test
    fun `одного замера залипания недостаточно`() {
        // Длинная загрузка наружу выглядит как сломанный туннель. Один замер —
        // ещё не повод рвать рабочее соединение.
        val outcome = StallRecoveryPolicy.decide(
            TunnelHealth.Stalled,
            StallRecoveryState.Initial,
            nowMillis = 1_000_000,
        )

        assertEquals(StallRecovery.Wait, outcome.decision)
        assertEquals(1, outcome.state.consecutiveStalled)
    }

    @Test
    fun `два замера подряд дают перезапуск`() {
        val first = StallRecoveryPolicy.decide(
            TunnelHealth.Stalled,
            StallRecoveryState.Initial,
            nowMillis = 1_000_000,
        )
        val second = StallRecoveryPolicy.decide(
            TunnelHealth.Stalled,
            first.state,
            nowMillis = 1_015_000,
        )

        assertEquals(StallRecovery.Restart, second.decision)
        assertEquals(1, second.state.attemptsUsed)
    }

    @Test
    fun `подтверждение начинается заново после перезапуска`() {
        // Свежий туннель должен получить те же полторы минуты, что и первый:
        // иначе каждая следующая попытка срабатывала бы от одного замера.
        val restarted = StallRecoveryPolicy.decide(
            TunnelHealth.Stalled,
            StallRecoveryState(consecutiveStalled = 1),
            nowMillis = 1_000_000,
        ).state

        assertEquals(0, restarted.consecutiveStalled)

        val afterRestart = StallRecoveryPolicy.decide(
            TunnelHealth.Stalled,
            restarted,
            nowMillis = 1_015_000,
        )

        assertEquals(
            StallRecovery.Wait,
            afterRestart.decision,
            "одного замера после перезапуска мало",
        )
    }

    @Test
    fun `второй перезапуск ждёт паузу`() {
        val afterFirst = StallRecoveryState(
            consecutiveStalled = 1,
            attemptsUsed = 1,
            lastAttemptAtMillis = 1_000_000,
        )

        val tooSoon = StallRecoveryPolicy.decide(
            TunnelHealth.Stalled,
            afterFirst,
            nowMillis = 1_010_000,
        )
        assertEquals(
            StallRecovery.Wait,
            tooSoon.decision,
            "слишком частая попытка — это цикл перезапусков, съедающий батарею",
        )

        val later = StallRecoveryPolicy.decide(
            TunnelHealth.Stalled,
            afterFirst,
            nowMillis = 1_000_000 + StallRecoveryPolicy.backoffFor(1),
        )
        assertEquals(StallRecovery.Restart, later.decision)
    }

    @Test
    fun `попытки кончаются, и правило сдаётся`() {
        val spent = StallRecoveryState(
            consecutiveStalled = 1,
            attemptsUsed = MAX_ATTEMPTS,
            lastAttemptAtMillis = 0,
        )

        val outcome = StallRecoveryPolicy.decide(
            TunnelHealth.Stalled,
            spent,
            nowMillis = 10_000_000,
        )

        assertEquals(StallRecovery.Exhausted(MAX_ATTEMPTS), outcome.decision)
    }

    @Test
    fun `исчерпание не сбрасывается живым замером`() {
        // Иначе туннель, оживающий на секунду между сбоями, перезапускался бы
        // бесконечно: попытки обнулялись бы при каждом «живом» замере.
        val spent = StallRecoveryState(
            consecutiveStalled = 0,
            attemptsUsed = MAX_ATTEMPTS,
            lastAttemptAtMillis = 0,
        )

        val alive = StallRecoveryPolicy.decide(
            TunnelHealth.Flowing,
            spent,
            nowMillis = 10_000_000,
        )

        assertEquals(MAX_ATTEMPTS, alive.state.attemptsUsed, "израсходованные попытки остаются")
    }

    @Test
    fun `паузы растут, а не топчутся на месте`() {
        val backoffs = (0 until MAX_ATTEMPTS).map { StallRecoveryPolicy.backoffFor(it) }

        assertEquals(0L, backoffs.first(), "сеть мигнула — лечится сразу")
        assertTrue(
            backoffs.zipWithNext().all { (a, b) -> b >= a },
            "пауза обязана не убывать, иначе попытки учащаются: $backoffs",
        )
    }

    @Test
    fun `период опроса не будит устройство слишком часто`() {
        assertTrue(
            StallRecoveryPolicy.POLL_INTERVAL_MILLIS >= 10_000,
            "частый опрос счётчиков тратит батарею",
        )
        assertTrue(
            StallRecoveryPolicy.STALL_AFTER_MILLIS >= StallRecoveryPolicy.POLL_INTERVAL_MILLIS * 3,
            "порог обязан быть заметно больше периода, иначе один пустой замер = сбой",
        )
    }
}
