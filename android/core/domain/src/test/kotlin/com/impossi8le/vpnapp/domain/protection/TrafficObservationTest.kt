package com.impossi8le.vpnapp.domain.protection

import com.impossi8le.vpnapp.domain.tunnel.TunnelCounters
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Прохождение трафика по счётчикам ядра.
 *
 * **Главное отличие от [com.impossi8le.vpnapp.domain.tunnel.TunnelStallDetectionTest].**
 * Там проверяется «пора перезапускать» и сброс счётчиков считается живым. Здесь
 * проверяется «состоялся ли измеренный круг», и те же входные данные обязаны
 * давать ДРУГОЙ ответ: сброс — это «не знаем», а не «жив». Эти тесты намеренно
 * дублируют сценарии залипания, чтобы зафиксировать расхождение: слить обе
 * функции в одну теперь нельзя, не сломав один из наборов.
 */
class TrafficObservationTest {

    private fun snapshot(
        out: Long,
        inb: Long,
        lastPacketAt: Long,
        at: Long,
        errors: Long = 0,
    ) = TunnelCounters(
        bytesOut = out,
        bytesIn = inb,
        lastPacketReceivedAtMillis = lastPacketAt,
        errorsOut = errors,
        takenAtMillis = at,
    )

    @Test
    fun `байты в обе стороны за окно — измеренный круг`() {
        val before = snapshot(out = 1_000, inb = 2_000, lastPacketAt = 100_000, at = 100_000)
        val after = snapshot(out = 5_000, inb = 9_000, lastPacketAt = 139_000, at = 140_000)

        assertEquals(
            TrafficVerdict.RoundTrip,
            TrafficObservation.observe(before, after),
        )
    }

    @Test
    fun `только наружу и давно нет ответа — вход без возврата`() {
        // Ключевой случай для щупа: запросы уходят, ответа нет дольше порога.
        // Это НЕ доказательство утечки, но единственный случай, о котором вердикт
        // говорит как о проблеме.
        val before = snapshot(out = 1_000, inb = 2_000, lastPacketAt = 100_000, at = 100_000)
        val after = snapshot(out = 9_000, inb = 2_000, lastPacketAt = 100_000, at = 240_000)

        assertEquals(
            TrafficVerdict.NoReturn,
            TrafficObservation.observe(before, after),
        )
    }

    @Test
    fun `полная тишина — простой, а не проблема`() {
        // Ничего не двигалось: пользователь молчит. Судить не о чем.
        val before = snapshot(out = 1_000, inb = 2_000, lastPacketAt = 100_000, at = 100_000)
        val after = before.copy(takenAtMillis = 240_000)

        assertEquals(TrafficVerdict.Idle, TrafficObservation.observe(before, after))
    }

    @Test
    fun `короткое окно не даёт вердикта даже при росте`() {
        // Рост есть, но окно короче минимума: круг мог просто не успеть. Ранний
        // вывод здесь стоил бы ложного «проблема» на всплеске трафика.
        val before = snapshot(out = 1_000, inb = 2_000, lastPacketAt = 100_000, at = 100_000)
        val after = snapshot(out = 5_000, inb = 9_000, lastPacketAt = 101_000, at = 110_000)

        assertEquals(
            TrafficVerdict.Inconclusive,
            TrafficObservation.observe(before, after, minWindowMillis = 30_000),
        )
    }

    @Test
    fun `сброс счётчиков — не знаем, а не круг`() {
        // Здесь и расходимся с детектором залипания: он назвал бы это `Flowing`.
        // Для защиты «счётчики обнулились» — отсутствие данных, а не
        // доказательство прохождения. Выводить зелёное из этого нельзя.
        val before = snapshot(out = 900_000, inb = 800_000, lastPacketAt = 400_000, at = 400_000)
        val after = snapshot(out = 10, inb = 5, lastPacketAt = 440_000, at = 440_000)

        assertEquals(TrafficVerdict.Inconclusive, TrafficObservation.observe(before, after))
    }

    @Test
    fun `растущие ошибки без ответа — вход без возврата`() {
        val before = snapshot(out = 1_000, inb = 2_000, lastPacketAt = 100_000, at = 100_000, errors = 1)
        val after = snapshot(out = 1_000, inb = 2_000, lastPacketAt = 100_000, at = 240_000, errors = 8)

        assertEquals(TrafficVerdict.NoReturn, TrafficObservation.observe(before, after))
    }

    @Test
    fun `свежий принятый пакет отменяет подозрение в отсутствии ответа`() {
        // Байты внутрь не прибавились (счётчик мог округлиться), но ядро приняло
        // пакет только что. Возраст последнего пакета около нуля — говорить о
        // «нет ответа дольше порога» неверно.
        val before = snapshot(out = 1_000, inb = 2_000, lastPacketAt = 100_000, at = 100_000)
        val after = snapshot(out = 9_000, inb = 2_000, lastPacketAt = 239_000, at = 240_000)

        assertEquals(TrafficVerdict.Idle, TrafficObservation.observe(before, after))
    }

    @Test
    fun `ответ давно, но моложе порога — пока не судим`() {
        val before = snapshot(out = 1_000, inb = 2_000, lastPacketAt = 100_000, at = 100_000)
        // Ответу 30 с — меньше порога в 90 с: рано обвинять туннель.
        val after = snapshot(out = 9_000, inb = 2_000, lastPacketAt = 210_000, at = 240_000)

        assertEquals(
            TrafficVerdict.Idle,
            TrafficObservation.observe(before, after, minWindowMillis = 30_000),
        )
    }

    @Test
    fun `только внутрь без исходящих — не круг`() {
        // Круг требует обе стороны внутри окна. Односторонний рост доказательством
        // прохождения не является.
        val before = snapshot(out = 1_000, inb = 2_000, lastPacketAt = 100_000, at = 100_000)
        val after = snapshot(out = 1_000, inb = 9_000, lastPacketAt = 139_000, at = 140_000)

        assertEquals(TrafficVerdict.Idle, TrafficObservation.observe(before, after))
    }
}
