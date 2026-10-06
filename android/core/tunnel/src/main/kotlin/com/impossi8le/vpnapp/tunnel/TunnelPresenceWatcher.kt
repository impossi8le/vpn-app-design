package com.impossi8le.vpnapp.tunnel

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Сторож существования туннеля.
 *
 * Пока состояние описывает поднятый туннель, раз в [interval] спрашивает у
 * системы, есть ли VPN-интерфейс, и при исчезновении отдаёт наружу
 * [ConnectionStatus.Disconnected].
 *
 * **Зачем это вообще.** `ESTABLISHED` приходит от ядра ОДНАЖДЫ и, если событие
 * `Disconnected` не придёт, становится вечной правдой. На телефоне это дало
 * экран «Подключено» при полностью отсутствующем `tun0` и открытом трафике через
 * `wlan0` (см. `docs/testing/2026-10-06-connected-without-tunnel.md`). Ядро может
 * замолчать — сервис переживает отзыв, систему сносит интерфейс без события, —
 * и единственный признак жизни тогда это опрос самого факта.
 *
 * **Почему `UNKNOWN` не гасит.** «Спросить не удалось» — не «туннеля нет».
 * Перевод по неизвестности рубил бы исправный туннель из-за случайного
 * исключения системного вызова. Неизвестность безопасна: состояние остаётся, а
 * опрос продолжается — следующий шаг либо подтвердит факт, либо честно его
 * опровергнет.
 *
 * Устроено как `ProtectionWatcher`: та же форма (перезапускаемый цикл, политика
 * отдельно, решения в чистой функции) — чтобы поведение проверялось на
 * виртуальном времени, а не ожиданием реальных секунд.
 */
class TunnelPresenceWatcher(
    private val probe: TunnelPresenceProbe,
    private val interval: Duration = DEFAULT_INTERVAL,
) {

    init {
        require(interval.isPositive()) { "интервал опроса туннеля должен быть положительным" }
    }

    private var job: Job? = null

    /**
     * Пересобрать наблюдение под текущее состояние.
     *
     * @param current снимок состояния на момент вызова и на каждом шаге цикла.
     *   Читается заново перед проверкой, а не берётся один раз: за [interval]
     *   состояние могло смениться (пользователь отключился, ядро прислало LOST),
     *   и гасить по устаревшему снимку значило бы затирать чужую, более свежую
     *   правду.
     * @param onTunnelLost куда отдать [ConnectionStatus.Disconnected], когда
     *   туннель исчез.
     */
    fun restart(
        scope: CoroutineScope,
        current: () -> ConnectionStatus,
        onTunnelLost: (ConnectionStatus) -> Unit,
    ) {
        // Старое задание отменяем ВСЕГДА: иначе каждая смена состояния оставляла
        // бы ещё один живой цикл, и опрос множился бы.
        job?.cancel()
        job = null
        // В покое и в подключении сторожа нет: там интерфейса либо ещё нет по
        // определению (`Connecting`), либо он и не должен быть.
        if (!current().describesTunnelUp) return

        job = scope.launch {
            while (isActive) {
                delay(interval)
                // Читаем состояние заново: за [interval] оно могло смениться
                // (пользователь отключился, ядро прислало LOST), и тогда сторож
                // больше не нужен.
                val before = current()
                if (!before.describesTunnelUp) return@launch
                // Решение принимает ЧИСТАЯ функция — та же, что проверена в
                // `TunnelPresenceMappingTest`. Здесь только факт из системы и
                // передача вердикта наружу.
                val after = before.reconcileWithTunnelPresence(probe.read())
                if (after != before) {
                    // Единственный возможный переход здесь — в Disconnected:
                    // туннель исчез. not Failed: соединение не «не состоялось».
                    onTunnelLost(after)
                    return@launch
                }
            }
        }
    }

    /**
     * Остановить наблюдение. Зовётся при отключении: живой цикл после него —
     * лишние опросы системы без предмета.
     */
    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /**
         * Как часто перепроверять существование туннеля.
         *
         * Несколько секунд — компромисс: смерть туннеля замечается прежде, чем
         * пользователь поверит значку системы, но опрос остаётся редким чтением
         * состояния, а не busy-loop. Дешевле полной пробы защиты (раз в минуту),
         * поэтому и чаще: здесь проверяется только факт интерфейса, а не маршруты
         * и DNS.
         */
        val DEFAULT_INTERVAL: Duration = 5.seconds
    }
}
