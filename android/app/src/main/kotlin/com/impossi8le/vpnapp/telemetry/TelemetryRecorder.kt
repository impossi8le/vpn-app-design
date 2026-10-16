package com.impossi8le.vpnapp.telemetry

import com.impossi8le.vpnapp.domain.diagnostics.EventType
import com.impossi8le.vpnapp.domain.diagnostics.ReportQueue
import com.impossi8le.vpnapp.domain.diagnostics.TelemetryEvent
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Запись событий работы туннеля в очередь на отправку.
 *
 * План описывал отправку, но не описывал, кто СОЗДАЁТ события, — очередь всегда
 * оставалась пустой, и авто-контур молчал: воркер честно ходил по расписанию, но
 * ему было нечего слать. Здесь закрывается этот пробел: изменение состояния
 * туннеля и ошибка попадают в очередь.
 *
 * `event_id` — UUID на событие: нужен для идемпотентности, чтобы повтор батча
 * после сбоя сети не создал дублей (контракт §6.1). Часы и генератор id — снаружи,
 * чтобы тест был детерминирован, а не зависел от системного времени.
 */
class TelemetryRecorder(
    private val queue: ReportQueue,
    private val clock: () -> String = { java.time.Instant.now().toString() },
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    private val json: Json = Json { encodeDefaults = false },
) {

    /** Записать переход состояния туннеля. */
    suspend fun stateChanged(
        state: String,
        configId: String?,
        configName: String?,
        server: String?,
    ) {
        enqueue(
            TelemetryEvent(
                eventId = idFactory(),
                type = EventType.StateChange,
                ts = clock(),
                state = state,
                configId = configId,
                configName = configName,
                server = server,
            ),
        )
    }

    /**
     * Записать ошибку с машинным кодом и текущим состоянием.
     *
     * Код — короткий идентификатор причины (`tunnel_failed`, `tun_prop_error`), а
     * не человеческий текст: по нему сервер строит статистику, не разбирая строку,
     * и в него нечего «дописать» лишнего.
     *
     * Контекст (`configId`/`configName`/`server`) — тот же, что у `state_change`:
     * без него по ошибке не видно, к какому серверу не подключилось, и разбор
     * жалобы «долго/не подключается» снова упирается в догадки.
     */
    suspend fun error(
        code: String,
        state: String?,
        configId: String? = null,
        configName: String? = null,
        server: String? = null,
    ) {
        enqueue(
            TelemetryEvent(
                eventId = idFactory(),
                type = EventType.Error,
                ts = clock(),
                state = state,
                configId = configId,
                configName = configName,
                server = server,
                code = code,
            ),
        )
    }

    /**
     * Записать итог попытки подключения и её длительность.
     *
     * Отдельный тип, а не `state_change`: по нему видно, сколько занимает
     * подключение, — метрика не выводится из одних только переходов.
     *
     * Контекст тот же, что у `state_change`: `server` без него в прод-данных
     * отсутствовал во всех записях, и по `connect_attempt` нельзя было понять,
     * к какому узлу не подключилось.
     */
    suspend fun connectAttempt(
        state: String,
        durationMs: Long,
        configId: String? = null,
        configName: String? = null,
        server: String? = null,
    ) {
        enqueue(
            TelemetryEvent(
                eventId = idFactory(),
                type = EventType.ConnectAttempt,
                ts = clock(),
                state = state,
                configId = configId,
                configName = configName,
                server = server,
                durationMs = durationMs,
            ),
        )
    }

    private suspend fun enqueue(event: TelemetryEvent) {
        queue.enqueue(json.encodeToString(TelemetryEvent.serializer(), event))
    }
}
