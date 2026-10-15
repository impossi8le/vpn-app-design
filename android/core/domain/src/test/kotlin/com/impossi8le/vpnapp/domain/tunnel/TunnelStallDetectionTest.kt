package com.impossi8le.vpnapp.domain.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Определение «туннель поднят, а трафик не идёт».
 *
 * **Главная трудность, ради которой это отдельный тип.** Нулевая скорость сама
 * по себе — норма: пользователь ничего не открывает, телефон спит, байты не
 * идут. Перезапуск в этот момент рвёт рабочее соединение и жжёт батарею, а
 * выглядит как самопроизвольный сбой приложения. Поэтому признак «нет байт» не
 * годится; нужен признак «БЫЛА активность, а результата нет».
 */
class TunnelStallDetectionTest {

    /** Снимок счётчиков: байты наружу/внутрь и время последнего принятого пакета. */
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
    fun `простой без трафика не считается залипанием`() {
        // Телефон лежит, приложений с сетью нет: байты не растут — и это норма.
        // Перезапуск здесь вреден, поэтому состояние обязано быть «простоит».
        val before = snapshot(out = 10_000, inb = 20_000, lastPacketAt = 1_000, at = 100_000)

        assertEquals(
            TunnelHealth.Idle,
            TunnelStallDetection.evaluate(
                before = before,
                after = before.copy(takenAtMillis = 200_000),
                stallAfterMillis = 30_000,
            ),
        )
    }

    @Test
    fun `активность без ответа считается залипанием`() {
        // Ключевой случай: наружу байты уходят (приложение просит), внутрь не
        // приходит ничего. Это не простой — это сломанный туннель.
        val before = snapshot(out = 1_000, inb = 5_000, lastPacketAt = 10_000, at = 100_000)
        val after = snapshot(out = 9_000, inb = 5_000, lastPacketAt = 10_000, at = 140_000)

        assertEquals(
            TunnelHealth.Stalled,
            TunnelStallDetection.evaluate(before, after, stallAfterMillis = 30_000),
        )
    }

    @Test
    fun `растущие байты внутрь — туннель живой`() {
        val before = snapshot(out = 1_000, inb = 5_000, lastPacketAt = 10_000, at = 100_000)
        val after = snapshot(out = 9_000, inb = 12_000, lastPacketAt = 139_000, at = 140_000)

        assertEquals(
            TunnelHealth.Flowing,
            TunnelStallDetection.evaluate(before, after, stallAfterMillis = 30_000),
        )
    }

    @Test
    fun `раньше срока не судим`() {
        // Прошло меньше порога — сказать нечего. Раннее срабатывание дороже
        // ожидания: оно рвёт рабочее соединение.
        val before = snapshot(out = 1_000, inb = 5_000, lastPacketAt = 10_000, at = 100_000)
        val after = snapshot(out = 9_000, inb = 5_000, lastPacketAt = 10_000, at = 110_000)

        assertEquals(
            TunnelHealth.Idle,
            TunnelStallDetection.evaluate(before, after, stallAfterMillis = 30_000),
        )
    }

    @Test
    fun `растущие ошибки — залипание даже без активности`() {
        // Ядро само сообщает об ошибках: ждать пользовательской активности
        // незачем, это явный сигнал поломки.
        val before = snapshot(out = 1_000, inb = 5_000, lastPacketAt = 10_000, at = 100_000, errors = 2)
        val after = snapshot(out = 1_000, inb = 5_000, lastPacketAt = 10_000, at = 140_000, errors = 7)

        assertEquals(
            TunnelHealth.Stalled,
            TunnelStallDetection.evaluate(before, after, stallAfterMillis = 30_000),
        )
    }

    @Test
    fun `свежий принятый пакет отменяет подозрение`() {
        // Внутрь байт не прибавилось (счётчик мог округлиться), но ядро says
        // пакет только что пришёл — значит связь есть.
        val before = snapshot(out = 1_000, inb = 5_000, lastPacketAt = 10_000, at = 100_000)
        val after = snapshot(out = 9_000, inb = 5_000, lastPacketAt = 139_000, at = 140_000)

        assertEquals(
            TunnelHealth.Flowing,
            TunnelStallDetection.evaluate(before, after, stallAfterMillis = 30_000),
        )
    }

    @Test
    fun `счётчики сбросились — не считаем это залипанием`() {
        // Перезапуск ядра или смена сессии обнуляет счётчики. Отрицательная
        // дельта — не «трафик встал», а новая точка отсчёта.
        val before = snapshot(out = 900_000, inb = 800_000, lastPacketAt = 90_000, at = 100_000)
        val after = snapshot(out = 10, inb = 5, lastPacketAt = 100_000, at = 140_000)

        assertEquals(
            TunnelHealth.Flowing,
            TunnelStallDetection.evaluate(before, after, stallAfterMillis = 30_000),
        )
    }

    @Test
    fun `активность есть, но давно не было принятых пакетов — залипание`() {
        // Даже если байты внутрь слегка растут (буфер ядра), при отсутствии
        // пакетов дольше порога туннель считать живым нельзя.
        val before = snapshot(out = 1_000, inb = 5_000, lastPacketAt = 10_000, at = 100_000)
        val after = snapshot(out = 9_000, inb = 5_100, lastPacketAt = 10_000, at = 140_000)

        assertEquals(
            TunnelHealth.Stalled,
            TunnelStallDetection.evaluate(before, after, stallAfterMillis = 30_000),
        )
    }
}
