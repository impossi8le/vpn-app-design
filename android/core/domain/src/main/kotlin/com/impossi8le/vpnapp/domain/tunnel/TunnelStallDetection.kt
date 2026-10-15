package com.impossi8le.vpnapp.domain.tunnel

/**
 * Снимок счётчиков туннеля на момент времени.
 *
 * Источник на Android — `ClientAPI_InterfaceStats` (tun) и
 * `ClientAPI_TransportStats` (транспорт) ядра OpenVPN 3. Здесь они приведены к
 * чистым числам: правило «залипло или нет» должно проверяться на JVM, а не на
 * телефоне с поднятым туннелем.
 */
data class TunnelCounters(
    /** Байт, отправленных в туннель. */
    val bytesOut: Long,
    /** Байт, принятых из туннеля. */
    val bytesIn: Long,
    /**
     * Когда ядро последний раз приняло пакет из транспорта, по локальным часам.
     *
     * Это самый точный признак жизни: байтовые счётчики могут не измениться
     * из-за округления, а «пакет пришёл» — событие.
     */
    val lastPacketReceivedAtMillis: Long,
    /** Ошибки интерфейса. Растут — ядру плохо, ждать активности незачем. */
    val errorsOut: Long,
    /** Когда снимок сделан. */
    val takenAtMillis: Long,
)

/** Что можно сказать о туннеле по двум снимкам. */
enum class TunnelHealth {
    /** Трафик идёт — трогать нечего. */
    Flowing,

    /**
     * Ничего не происходит: ни трафика, ни ошибок. Это НЕ поломка — так
     * выглядит телефон в кармане, и перезапуск здесь только навредит.
     */
    Idle,

    /** Была активность или ошибки, а результата нет. Туннель надо перезапустить. */
    Stalled,
}

/**
 * «Туннель поднят, а трафик не идёт» — по двум снимкам счётчиков.
 *
 * **Почему не «скорость упала до нуля».** Ноль байт в секунду — нормальное
 * состояние: пользователь ничего не открывает, телефон спит. Признак сбоя
 * обязан отличать простой от поломки, иначе автоперезапуск будет рвать рабочее
 * соединение каждые несколько минут.
 *
 * Отличаем так:
 *  - байты НАРУЖУ выросли, а внутрь — нет дольше [stallAfterMillis]: запросы
 *    уходят, ответов нет → [TunnelHealth.Stalled];
 *  - ошибки интерфейса выросли → [TunnelHealth.Stalled] (ядро само сообщило о
 *    поломке, ждать пользовательской активности незачем);
 *  - байты внутрь выросли или ядро приняло свежий пакет → [TunnelHealth.Flowing];
 *  - ничего из перечисленного → [TunnelHealth.Idle].
 *
 * **Сброс счётчиков не считается залипанием.** Перезапуск ядра обнуляет числа;
 * отрицательная дельта означает новую точку отсчёта, а не «трафик встал».
 *
 * @param stallAfterMillis сколько ждать отсутствия ответов, прежде чем судить.
 *   Меньше порога — «не знаем» ([TunnelHealth.Idle]), а не догадка: раннее
 *   срабатывание дороже ожидания.
 */
object TunnelStallDetection {

    fun evaluate(
        before: TunnelCounters,
        after: TunnelCounters,
        stallAfterMillis: Long,
    ): TunnelHealth {
        val elapsed = after.takenAtMillis - before.takenAtMillis
        val bytesOutDelta = after.bytesOut - before.bytesOut
        val bytesInDelta = after.bytesIn - before.bytesIn
        val errorsDelta = after.errorsOut - before.errorsOut

        // Счётчики обнулились (перезапуск ядра): сравнивать не с чем, но и
        // поломки здесь нет.
        if (bytesOutDelta < 0 || bytesInDelta < 0) return TunnelHealth.Flowing

        // Ошибки — прямая жалоба ядра, порога ожидания не требуют.
        if (errorsDelta > 0) return TunnelHealth.Stalled

        // Свежий принятый пакет — связь есть, даже если байты не шевельнулись.
        if (after.lastPacketReceivedAtMillis > before.lastPacketReceivedAtMillis) {
            return TunnelHealth.Flowing
        }

        // Раньше срока не судим.
        if (elapsed < stallAfterMillis) return TunnelHealth.Idle

        // Запросы уходят, ответов нет дольше порога.
        if (bytesOutDelta > 0) return TunnelHealth.Stalled

        // Ни запросов, ни ответов: телефон простаивает, а не сломался.
        return TunnelHealth.Idle
    }
}
