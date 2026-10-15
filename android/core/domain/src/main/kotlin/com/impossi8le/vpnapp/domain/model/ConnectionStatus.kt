package com.impossi8le.vpnapp.domain.model

import com.impossi8le.vpnapp.domain.protection.ProtectionEvidence
import com.impossi8le.vpnapp.domain.protection.ProtectionVerdict

/**
 * Состояние соединения — НАША модель, а не состояние VpnService и не сырое
 * состояние ядра OpenVPN. Системное состояние переводится сюда явным маппингом
 * в core:tunnel и в домен не протекает.
 */
sealed interface ConnectionStatus {
    /**
     * Член интерфейса, а не extension-свойство: extension нужно импортировать в
     * каждый файл, и забытый импорт ломает сборку у потребителя. Здесь оно есть
     * везде, где есть сам тип.
     */
    val isProtected: Boolean get() = this is Protected

    data object Disconnected : ConnectionStatus

    data object Connecting : ConnectionStatus

    /**
     * Соединение восстанавливается после сбоя: туннель был поднят, перестал
     * отвечать, и сейчас перезапускается.
     *
     * **Зачем отдельное состояние, а не [Connecting].** Автоперезапуск шёл через
     * «отключено → подключение → подключено», и экран молча прыгал между
     * состояниями: пользователь видел, что соединение пропало, и не понимал,
     * само ли оно вернётся. Хуже того, во время перезапуска трафик **идёт
     * напрямую** — туннеля в эти секунды нет, — и подача обязана об этом
     * сказать, а не показывать безобидное «Подключение».
     *
     * От [Connecting] отличается тем, что это не первое соединение, а
     * восстановление уже работавшего: пользователю важно знать, что перебои
     * временны и приложение само их чинит.
     */
    data object Recovering : ConnectionStatus

    /**
     * Туннель поднят, но защита НЕ подтверждена замером.
     *
     * Именно в это состояние маппится системное «подключено». Операционная
     * система в этот момент уже рисует значок VPN, а пользователю показывается
     * «не проверено» — потому что поднятый интерфейс не доказывает, что трафик
     * идёт через него.
     */
    data object VerifyingProtection : ConnectionStatus

    /**
     * Единственное зелёное. Несёт evidence, который можно получить только
     * от ProtectionGate, — то есть только из состоявшегося замера.
     */
    data class Protected(val evidence: ProtectionEvidence) : ConnectionStatus

    /**
     * Замер провалился. Отдельный третий исход: не вечное «проверяем» и не
     * ложное зелёное. Пользователь должен узнать, что защиты нет.
     */
    data class ProtectionFailed(val verdict: ProtectionVerdict.Failed) : ConnectionStatus

    /** Туннель не поднялся по причине, не связанной с пробой. */
    data class Failed(val reason: String) : ConnectionStatus
}
