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
 * Следит за тем, чтобы подтверждение не жило дольше, чем правда о нём.
 *
 * Два источника перепроверки, оба нужны:
 *  - **смена сети** — самый частый случай, когда защита перестаёт быть правдой
 *    без какого-либо признака в UI (тот же статус «поднято», другая сеть);
 *  - **таймер** — интерфейс может перезапуститься, ядро потерять рукопожатие,
 *    маршрут измениться без события смены сети вовсе.
 *
 * Вынесено из ViewModel, потому что это самостоятельная ответственность с
 * собственной политикой, и её надо проверять отдельно от логики экрана.
 */
class ProtectionWatcher(
    private val gate: ProtectionGate,
    private val policy: ReverificationPolicy = ReverificationPolicy.Default,
) {

    private var timerJob: Job? = null

    /**
     * Запускает наблюдение.
     *
     * @param networkChanges поток событий смены сети; при [ReverificationPolicy.onNetworkChange]
     *   каждое событие вызывает немедленную перепроверку.
     * @param onVerdict что делать с результатом: экран кладёт его в своё состояние.
     * @param onInvalidated вызывается перед перепроверкой, если политика требует
     *   сбросить прежнее подтверждение не дожидаясь вердикта.
     */
    fun start(
        scope: CoroutineScope,
        networkChanges: Flow<Unit>,
        onVerdict: (ConnectionStatus) -> Unit,
        onInvalidated: () -> Unit,
    ) {
        stop()

        if (policy.onNetworkChange) {
            scope.launch {
                networkChanges.collect {
                    if (policy.clearBeforeReverify) onInvalidated()
                    onVerdict(gate.evaluate())
                }
            }
        }

        timerJob = scope.launch {
            while (isActive) {
                delay(policy.interval)
                // Периодический замер НЕ сбрасывает подтверждение заранее:
                // иначе индикатор мигал бы каждую минуту. Если замер провалится,
                // вердикт сам заменит зелёное на «не подтверждено».
                onVerdict(gate.evaluate())
            }
        }
    }

    fun stop() {
        timerJob?.cancel()
        timerJob = null
    }
}
