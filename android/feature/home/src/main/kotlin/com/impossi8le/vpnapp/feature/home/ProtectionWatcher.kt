package com.impossi8le.vpnapp.feature.home

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionGate
import com.impossi8le.vpnapp.domain.protection.ReverificationPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Следит за тем, чтобы подтверждение не жило дольше правды о нём.
 *
 * Два источника перепроверки, оба нужны:
 *  - **смена сети** — самый частый случай, когда защита перестаёт быть правдой
 *    без какого-либо признака в UI (тот же статус «поднято», другая сеть);
 *  - **таймер** — интерфейс может перезапуститься, ядро потерять рукопожатие,
 *    маршрут измениться без события смены сети вовсе.
 *
 * Вынесено из ViewModel: это самостоятельная ответственность со своей политикой.
 */
class ProtectionWatcher(
    private val gate: ProtectionGate,
    private val policy: ReverificationPolicy = ReverificationPolicy.Default,
) {

    // Оба задания держатся вместе. Если хранить только таймер, подписка на
    // смену сети переживёт stop(): повторный start добавит вторую подписку,
    // и одно событие сети вызовет два замера. Именно поэтому здесь список,
    // а не одиночное поле.
    private var jobs: List<Job> = emptyList()

    /**
     * Запускает наблюдение.
     *
     * @param onVerdict результат замера: экран кладёт его в своё состояние.
     * @param onInvalidated вызывается перед перепроверкой, если политика требует
     *   сбросить прежнее подтверждение, не дожидаясь вердикта.
     */
    fun start(
        scope: CoroutineScope,
        networkChanges: Flow<Unit>,
        onVerdict: (ConnectionStatus) -> Unit,
        onInvalidated: () -> Unit,
    ) {
        stop()

        val started = mutableListOf<Job>()

        if (policy.onNetworkChange) {
            started += scope.launch {
                networkChanges.collect {
                    if (policy.clearBeforeReverify) onInvalidated()
                    onVerdict(gate.evaluate())
                }
            }
        }

        started += scope.launch {
            while (isActive) {
                delay(policy.interval)
                // Периодический замер НЕ сбрасывает подтверждение заранее:
                // иначе индикатор мигал бы каждый интервал. Если замер провалится,
                // вердикт сам заменит зелёное на «не подтверждено».
                onVerdict(gate.evaluate())
            }
        }

        jobs = started
    }

    /** Останавливает наблюдение целиком: и подписку, и таймер. */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }
}
