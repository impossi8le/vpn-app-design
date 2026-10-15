package com.impossi8le.vpnapp.domain.tunnel

/**
 * Сколько раз и как часто пробовали перезапустить залипший туннель.
 *
 * Состояние держит наблюдатель (Android-сторона), а ПЕРЕХОДЫ считает
 * [StallRecoveryPolicy] — иначе правило «когда перезапускать» проверялось бы
 * только на телефоне с настоящим залипанием, то есть почти никогда.
 */
data class StallRecoveryState(
    /** Сколько подряд замеров подряд показали залипание. */
    val consecutiveStalled: Int = 0,
    /** Сколько перезапусков уже израсходовано. */
    val attemptsUsed: Int = 0,
    /** Когда был последний перезапуск; `null` — ещё не перезапускали. */
    val lastAttemptAtMillis: Long? = null,
) {
    companion object {
        val Initial = StallRecoveryState()
    }
}

/** Что делать по результату замера. */
sealed interface StallRecovery {
    /** Ничего: либо всё в порядке, либо ждём backoff. */
    data object Wait : StallRecovery

    /** Перезапустить подключение. */
    data object Restart : StallRecovery

    /** Попытки исчерпаны — сообщить пользователю и оставить как есть. */
    data class Exhausted(val attempts: Int) : StallRecovery
}

/** Решение вместе с новым состоянием: вызывающему не нужно считать переходы самому. */
data class StallRecoveryOutcome(
    val decision: StallRecovery,
    val state: StallRecoveryState,
)

/**
 * Правило автоперезапуска: когда будить соединение, а когда смириться.
 *
 * **Почему нужен второй подтверждающий замер.** [TunnelStallDetection] уже
 * требует, чтобы активность была без ответа дольше порога. Но и это может
 * ошибиться: длинная загрузка наружу выглядит так же, как сломанный туннель.
 * Перезапуск рвёт рабочее соединение, поэтому просим подтверждение со следующего
 * опроса — это стоит одного интервала, а ложное срабатывание стоит пользователю
 * упавшей загрузки.
 *
 * **Почему попытки ограничены.** Без предела при действительно мёртвом сервере
 * получился бы цикл перезапусков, который съедает батарею и выглядит как
 * бесконечно мигающий экран. Исчерпав попытки, правило СДАЁТСЯ и говорит
 * пользователю правду: соединение не оживает само.
 *
 * **Почему интервалы растут.** Первый перезапуск — почти сразу (сеть мигнула,
 * это лечится мгновенно). Дальше ждём дольше: если причина в сервере, частые
 * попытки ничего не изменят, а батарею потратят.
 */
object StallRecoveryPolicy {

    /**
     * Как часто опрашивать счётчики ядра.
     *
     * Опрос будит устройство, поэтому период не может быть маленьким. 15 секунд —
     * компромисс: заметить залипание за десятки секунд, а не за минуты, и при
     * этом не держать телефон в постоянном пробуждении.
     */
    const val POLL_INTERVAL_MILLIS = 15_000L

    /**
     * Сколько активности без ответа считать залипанием.
     *
     * Заметно больше периода опроса: один-два пустых замера ещё ничего не значат,
     * а вот полторы минуты без единого принятого пакета при растущем исходящем
     * трафике — уже поломка.
     */
    const val STALL_AFTER_MILLIS = 90_000L

    /** Сколько перезапусков подряд пробуем, прежде чем сдаться. */
    const val MAX_ATTEMPTS = 4

    /**
     * Пауза перед следующей попыткой: индекс — номер уже сделанной попытки.
     *
     * Первый перезапуск без паузы (мигнувшая сеть лечится сразу), дальше с
     * нарастанием. Последнее значение повторяется, если попыток окажется больше.
     */
    val BACKOFF_MILLIS = listOf(0L, 30_000L, 120_000L, 300_000L)

    /** Пауза перед попыткой номер `attemptsUsed`. */
    fun backoffFor(attemptsUsed: Int): Long =
        BACKOFF_MILLIS.getOrElse(attemptsUsed) { BACKOFF_MILLIS.last() }

    /**
     * Что делать по свежему замеру.
     *
     * Живой или простаивающий туннель сбрасывает счётчик подряд идущих залипаний,
     * но НЕ обнуляет израсходованные попытки: иначе туннель, оживающий на секунду
     * между сбоями, перезапускался бы бесконечно.
     */
    fun decide(
        health: TunnelHealth,
        state: StallRecoveryState,
        nowMillis: Long,
    ): StallRecoveryOutcome {
        if (health != TunnelHealth.Stalled) {
            return StallRecoveryOutcome(
                decision = StallRecovery.Wait,
                state = state.copy(consecutiveStalled = 0),
            )
        }

        val confirmed = state.consecutiveStalled + 1
        val stalledNow = state.copy(consecutiveStalled = confirmed)

        // Одного замера мало: см. «почему нужен второй подтверждающий замер».
        if (confirmed < CONFIRMATIONS_REQUIRED) {
            return StallRecoveryOutcome(StallRecovery.Wait, stalledNow)
        }

        if (state.attemptsUsed >= MAX_ATTEMPTS) {
            return StallRecoveryOutcome(
                decision = StallRecovery.Exhausted(state.attemptsUsed),
                state = stalledNow,
            )
        }

        val waitedFor = state.lastAttemptAtMillis?.let { nowMillis - it } ?: Long.MAX_VALUE
        if (waitedFor < backoffFor(state.attemptsUsed)) {
            return StallRecoveryOutcome(StallRecovery.Wait, stalledNow)
        }

        return StallRecoveryOutcome(
            decision = StallRecovery.Restart,
            state = stalledNow.copy(
                attemptsUsed = state.attemptsUsed + 1,
                lastAttemptAtMillis = nowMillis,
                // После перезапуска подтверждение начинается заново: свежий
                // туннель получает те же полторы минуты, что и первый.
                consecutiveStalled = 0,
            ),
        )
    }

    /** Сколько подряд замеров нужно, чтобы счесть залипание подтверждённым. */
    const val CONFIRMATIONS_REQUIRED = 2
}
