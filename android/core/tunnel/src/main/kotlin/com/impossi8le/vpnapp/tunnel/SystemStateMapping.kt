package com.impossi8le.vpnapp.tunnel

import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionEvidence

/**
 * Системное состояние VpnService, как его отдаёт платформа.
 *
 * В домен этот тип не протекает: он живёт в core:tunnel и переводится в
 * ConnectionStatus явным маппингом ниже.
 */
enum class SystemState { IDLE, CONNECTING, ESTABLISHED, LOST, FAILED }

/**
 * Маппинг системного состояния в доменное.
 *
 * ESTABLISHED даёт VerifyingProtection, а не Protected, и это не деталь UI —
 * это инвариант: поднятый интерфейс не доказывает, что трафик идёт через него.
 * Функция физически не способна вернуть Protected: у неё нет ProtectionEvidence,
 * а конструктор evidence — `internal` в core:domain. Чтобы скомпилировать
 * Protected здесь, пришлось бы сначала ослабить саму защиту в домене.
 */
fun SystemState.toConnectionStatus(): ConnectionStatus = when (this) {
    SystemState.IDLE -> ConnectionStatus.Disconnected
    SystemState.CONNECTING -> ConnectionStatus.Connecting
    SystemState.ESTABLISHED -> ConnectionStatus.VerifyingProtection
    SystemState.LOST -> ConnectionStatus.Disconnected
    SystemState.FAILED -> ConnectionStatus.Failed("туннель не поднялся")
}

/**
 * Пересмотреть «подключено» по проверяемому факту существования туннеля.
 *
 * `ESTABLISHED` — это событие ядра ОДНАЖДЫ. Пока его считали правдой без срока
 * годности, экран оставался в «Подключено» и после того, как туннель исчез:
 * `ip route` в этот момент гнал весь трафик открытым через `wlan0`, а интерфейса
 * `tun0` в системе не было вовсе (см.
 * `docs/testing/2026-10-06-connected-without-tunnel.md`). Ядро при этом молчит —
 * события `Disconnected`, которое вернуло бы состояние, не приходит. Единственный
 * способ не соврать — периодически спрашивать у системы, есть ли туннель, и это
 * пересмотр.
 *
 * Три исхода, и все три обязаны быть разными:
 *  - туннеля нет — [ConnectionStatus.Disconnected], а НЕ [ConnectionStatus.Failed]:
 *    подключение не «не состоялось», оно было и кончилось. «Не удалось
 *    подключиться» с кнопкой «Повторить» здесь — неверный текст про верный факт;
 *  - спросить не удалось ([TunnelPresence.UNKNOWN]) — состояние не трогаем:
 *    неизвестность не доказательство отсутствия, и переводить по ней исправный
 *    туннель в отключено значило бы рубить связь из-за случайного сбоя опроса;
 *  - туннель на месте — состояние остаётся как есть, зелёным оно стать не может:
 *    функция не создаёт новый [ConnectionStatus], только пропускает или гасит
 *    прежний. Так §6 не слабеет — `Protected` по-прежнему приходит только от
 *    замера `ProtectionGate`.
 *
 * Гасит только «поднятые» состояния ([ConnectionStatus.VerifyingProtection] и его
 * же производные после замера). `Connecting` трогать нельзя: интерфейса в этот
 * момент ещё нет по определению, и проверка «туннеля нет» гнала бы нормальный
 * процесс в отключено.
 *
 * Функция чистая: решение принимается здесь, факт читает [TunnelPresenceProbe].
 */
fun ConnectionStatus.reconcileWithTunnelPresence(presence: TunnelPresence): ConnectionStatus {
    if (!describesTunnelUp) return this
    return when (presence) {
        TunnelPresence.PRESENT, TunnelPresence.UNKNOWN -> this
        TunnelPresence.ABSENT -> ConnectionStatus.Disconnected
    }
}

/**
 * Описывает ли состояние ПОДНЯТЫЙ туннель — только такие состояния и стоит
 * перепроверять.
 *
 * Единый источник правды для «какие состояния держат туннель поднятым»: тот же
 * список использует и [reconcileWithTunnelPresence], и сторож в
 * `AppTunnelController`, который решает, запускать ли опрос. Разъехавшись, они
 * либо опрашивали бы `Connecting` (интерфейса там ещё нет — нормальный процесс
 * гнался бы в отключено), либо перестали бы замечать смерть туннеля.
 *
 * `Connecting` здесь нет намеренно: туннель поднимается, и отсутствие
 * интерфейса — это нормальное промежуточное состояние, а не исчезновение.
 */
val ConnectionStatus.describesTunnelUp: Boolean
    get() = this is ConnectionStatus.VerifyingProtection ||
        this is ConnectionStatus.Protected ||
        this is ConnectionStatus.ProtectionFailed
