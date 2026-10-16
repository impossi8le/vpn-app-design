package com.impossi8le.vpnapp.vpnservice

import com.impossi8le.vpnapp.domain.tunnel.TunnelState
import com.impossi8le.vpnapp.domain.tunnel.TunnelStateStore

/**
 * Публикация состояния сервиса в общий источник правды.
 *
 * **Почему это здесь, а не в домене.** Разбирать `SystemState.name` умеет только
 * тот, кто этот `SystemState` и объявляет, — `:vpnservice`. Домен о системном
 * состоянии не знает и знать не должен: он хранит уже разобранное [TunnelState].
 * Разделение ровно то же, что у `TunnelStatusReceiver`: строки контракта остаются
 * на той стороне, которая их пишет.
 *
 * **Почему один предмет на троих.** Раньше снимок жил прямо здесь и был виден
 * сервису и плитке, но НЕ контроллеру в `:core:tunnel` — а тот владеет сторожем
 * существования туннеля и обязан гасить состояние, когда интерфейс исчез. Тогда
 * экран говорил «не подключено», а плитка продолжала показывать «подключён».
 * Теперь предмет живёт в [TunnelStateStore], общем для всех трёх модулей, и это
 * расхождение невозможно по построению.
 */
object TunnelStateSnapshot {

    /**
     * Опубликовать имя системного состояния.
     *
     * Зовётся СТРОГО вместе с широковещанием состояния: сервис не должен
     * сообщить экрану одно, а плитке — другое.
     */
    fun publish(stateName: String) {
        TunnelStateStore.publish(fromSystemName(stateName))
    }

    /** Что видит читатель прямо сейчас. */
    fun read(): TunnelState = TunnelStateStore.state.value

    /**
     * Имя состояния сервиса → состояние для показа.
     *
     * Незнакомую строку считаем незнанием, а не «выключено»: так после
     * обновления сервиса плитка не покажет выключенным уже поднятый туннель.
     */
    fun fromSystemName(stateName: String?): TunnelState = when (stateName) {
        // Поднятый туннель. `ESTABLISHED` — ровно «интерфейс поднят», и это не
        // защита: §6 запрещает обещать больше.
        "ESTABLISHED" -> TunnelState.Established
        "CONNECTING" -> TunnelState.Connecting
        // Восстановление для показа — это «идёт процесс»: туннеля сейчас нет,
        // но и выключенным он не был. «Отключено» соврало бы про намерение,
        // «подключено» — про факт.
        "RECOVERING" -> TunnelState.Recovering
        "IDLE", "LOST", "FAILED" -> TunnelState.Idle
        else -> TunnelState.Unknown
    }
}
