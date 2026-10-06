package com.impossi8le.vpnapp.feature.home

import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Подача главного экрана: только «подключено / нет» и какое подключение работает.
 *
 * Экран больше НЕ говорит о защите. Проба защиты — заглушка, поэтому тексты
 * «трафик не идёт через туннель» и «проверка не пройдена» ничего не измеряли.
 * Доменный инвариант §6 при этом не ослаблен: он живёт в `core:domain`
 * (`ProtectionGate`), а не в текстах. Здесь проверяется ровно то, о чём просил
 * пользователь: что интерфейс поднят и какой конфиг под ним.
 *
 * Красный по-прежнему означает только реальную опасность, а в состояниях
 * «туннель поднят» его нет: соединение не разорвано.
 */
class StatusPresentationTest {

    @Test
    fun `поднятый туннель показывается подключением без слова о защите`() {
        // Три доменных исхода «туннель поднят» — один и тот же текст.
        listOf(
            ConnectionStatus.VerifyingProtection,
            protectedStatus(),
            failedStatus(),
        ).forEach { status ->
            val presentation = status.presentation()
            assertEquals("Подключено", presentation.title, "для $status")
            assertTrue(
                !presentation.title.contains("защит", ignoreCase = true) &&
                    !presentation.detail.contains("защит", ignoreCase = true),
                "интерфейс не может утверждать защиту: проба её не подтверждает",
            )
        }
    }

    @Test
    fun `имя конфига показывается подзаголовком при поднятом туннеле`() {
        val presentation = ConnectionStatus.VerifyingProtection.presentation("Нидерланды")

        assertEquals("Подключено", presentation.title)
        assertEquals("Нидерланды", presentation.detail)
    }

    @Test
    fun `без имени конфига подзаголовок нейтрален`() {
        val presentation = ConnectionStatus.VerifyingProtection.presentation(null)

        assertEquals("Подключено", presentation.title)
        assertTrue(presentation.detail.isNotBlank())
        assertEquals("Соединение установлено", presentation.detail)
    }

    @Test
    fun `при активных обходах подробность говорит, что часть трафика идёт напрямую`() {
        // «Подключено» при обходах означает «не всё через туннель» — молчать об
        // этом значит повторять ту ложную уверенность, против которой §6.
        val p = ConnectionStatus.VerifyingProtection.presentation(
            runningConfigName = "Нидерланды",
            bypassCount = 6,
        )

        assertEquals("Подключено", p.title)
        assertTrue(
            p.detail.contains("напрямую") || p.detail.contains("обход"),
            "подробность должна сказать о частичном обходе, было: ${p.detail}",
        )
    }

    @Test
    fun `без обходов подробность как прежде`() {
        val p = ConnectionStatus.VerifyingProtection.presentation("Нидерланды", bypassCount = 0)
        assertEquals("Нидерланды", p.detail)
    }

    @Test
    fun `янтарный только у идущего процесса`() {
        assertEquals(VpnColors.Amber, ConnectionStatus.Connecting.presentation().accent)

        listOf(ConnectionStatus.Disconnected, ConnectionStatus.VerifyingProtection)
            .forEach {
                assertNotEquals(
                    VpnColors.Amber,
                    it.presentation().accent,
                    "янтарный означает процесс, а $it процессом не является",
                )
            }
    }

    @Test
    fun `переходное состояние подключения не читается как подключено`() {
        // Нажатие кнопки сразу даёт Connecting, а не VerifyingProtection. Пока
        // туннель не поднят, заголовок «Подключено» был бы ложью: он обещал бы
        // соединение, которого ещё нет. Заголовок обязан отличаться.
        assertNotEquals(
            "Подключено",
            ConnectionStatus.Connecting.presentation().title,
            "пока соединение устанавливается, «Подключено» показывать нельзя",
        )
    }

    @Test
    fun `красный только у реальной опасности`() {
        // Красный — сигнал разрыва, когда подключение НЕ состоялось. Проверяем
        // оба входа в Failed (нет сети и отказ ядра), чтобы правило держалось на
        // самом состоянии, а не на конкретной причине.
        assertEquals(VpnColors.Red, ConnectionStatus.Failed("нет сети").presentation().accent)
        assertEquals(VpnColors.Red, ConnectionStatus.Failed("отказ ядра").presentation().accent)

        // У поднятого туннеля разрыва нет — красный запрещён, иначе он обесценится
        // на настоящей опасности. Проверяем все три исхода «туннель поднят».
        listOf(
            ConnectionStatus.VerifyingProtection,
            protectedStatus(),
            failedStatus(),
        ).forEach {
            assertNotEquals(
                VpnColors.Red,
                it.presentation().accent,
                "разрыва нет — красный здесь кричал бы ложно ($it)",
            )
        }
    }

    @Test
    fun `отключённое состояние нейтрально, а не красное`() {
        // Отключился — это не опасность, а покой. Красный обесценился бы.
        assertNotEquals(VpnColors.Red, ConnectionStatus.Disconnected.presentation().accent)
    }

    @Test
    fun `у каждого состояния есть непустой текст`() {
        val all = listOf(
            ConnectionStatus.Disconnected,
            ConnectionStatus.Connecting,
            ConnectionStatus.VerifyingProtection,
            protectedStatus(),
            failedStatus(),
            ConnectionStatus.Failed("причина"),
        )
        all.forEach {
            val p = it.presentation()
            assertTrue(p.title.isNotBlank(), "заголовок пуст для $it")
            assertTrue(p.detail.isNotBlank(), "пояснение пусто для $it")
        }
    }

    @Test
    fun `причина сбоя подключения показывается пользователю`() {
        val presentation = ConnectionStatus.Failed("сервер недоступен").presentation()
        assertTrue(presentation.detail.contains("сервер недоступен"))
    }

    @Test
    fun `текст кнопки соответствует матрице действий`() {
        // Матрица из макета. Проверяем дословно, потому что неверная подпись
        // обещает действие, которого не будет.
        assertEquals("Подключить", ConnectionStatus.Disconnected.presentation().actionLabel)
        assertEquals("Отключить", protectedStatus().presentation().actionLabel)
        assertEquals("Отключить", ConnectionStatus.VerifyingProtection.presentation().actionLabel)
        assertEquals("Повторить", ConnectionStatus.Failed("нет сети").presentation().actionLabel)

        val connecting = ConnectionStatus.Connecting.presentation()
        // В подключении кнопка ОТМЕНЯЕТ попытку, а не отключает: отключать ещё
        // нечего, туннель не поднят. Подпись «Отключить» здесь врала бы.
        assertEquals("Отменить", connecting.actionLabel)
        assertEquals(
            StatusAction.Cancel,
            connecting.action,
            "отмена отличается от отключения: отключать нечего",
        )
    }

    // Вспомогательные: собрать Protected и ProtectionFailed можно только теми
    // путями, которые предусмотрены доменом.
    private fun protectedStatus(): ConnectionStatus =
        ConnectionStatus.Protected(
            (ProtectionVerdict.evaluate(true, true, true) as ProtectionVerdict.Confirmed).evidence,
        )

    private fun failedStatus(): ConnectionStatus =
        ConnectionStatus.ProtectionFailed(
            // Замер состоялся, но признак не подтвердился: маршрут IPv6 не закрыт.
            ProtectionVerdict.evaluate(ipv4InTunnel = false, ipv6Closed = true, dnsInside = true)
                as ProtectionVerdict.Failed,
        )
}
