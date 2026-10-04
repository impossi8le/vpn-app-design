package com.impossi8le.vpnapp.feature.home

import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionFailure
import com.impossi8le.vpnapp.domain.protection.ProtectionVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Ложный зелёный — главный дефект, найденный на UX-ревью макета: заголовок
 * «Туннель поднят» был зелёным при соседней надписи «не проверено».
 *
 * Эти тесты падают, если кто-то снова свяжет зелёный цвет с поднятым
 * интерфейсом вместо результата замера.
 */
class StatusPresentationTest {

    @Test
    fun `зелёный цвет только у подтверждённой защиты`() {
        val protected = protectedStatus().presentation()
        // Инвариант тот же: зелёный приходит только из состоявшегося замера.
        assertEquals(VpnColors.Green, protected.accent)
        // Заголовок обновлён дословно по макету («Подключено и защищено»), но
        // проверка не про красоту текста, а про то, что зелёный и слово
        // «защищено» стоят рядом только у подтверждённого состояния.
        assertEquals("Подключено и защищено", protected.title)
    }

    @Test
    fun `поднятый туннель не зелёный и говорит что не проверен`() {
        val presentation = ConnectionStatus.VerifyingProtection.presentation()

        assertNotEquals(
            VpnColors.Green,
            presentation.accent,
            "поднятый интерфейс не доказывает, что трафик идёт через туннель",
        )
        assertTrue(
            presentation.detail.contains("не проверен", ignoreCase = true),
            "пользователь должен видеть, что защиты ещё нет",
        )
    }

    @Test
    fun `ни одно состояние кроме подтверждённого не зелёное`() {
        val all = listOf(
            ConnectionStatus.Disconnected,
            ConnectionStatus.Connecting,
            ConnectionStatus.VerifyingProtection,
            ConnectionStatus.Failed("нет сети"),
            failedStatus(),
        )
        all.forEach { status ->
            assertNotEquals(
                VpnColors.Green,
                status.presentation().accent,
                "зелёный недопустим для состояния $status",
            )
        }
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
    fun `красный только у реальной опасности`() {
        // Красный — сигнал разрыва, когда подключение НЕ состоялось. Проверяем
        // оба входа в Failed (нет сети и отказ ядра), чтобы правило держалось на
        // самом состоянии, а не на конкретной причине.
        assertEquals(VpnColors.Red, ConnectionStatus.Failed("нет сети").presentation().accent)
        assertEquals(VpnColors.Red, ConnectionStatus.Failed("отказ ядра").presentation().accent)

        // Новое правило по макету: у ProtectionFailed соединение есть, туннель
        // поднят, не подтвердился только замер. Это внимание (янтарный), а не
        // разрыв, поэтому красный здесь запрещён — иначе он обесценится на
        // настоящей опасности. Проверяем не только «не красный», но и «не
        // зелёный»: это важнее исходного утверждения, потому что именно тут
        // раньше можно было случайно показать ложную защиту.
        val protectionFailed = failedStatus().presentation().accent
        assertEquals(VpnColors.Amber, protectionFailed)
        assertNotEquals(VpnColors.Red, protectionFailed, "разрыва нет — красный здесь кричал бы ложно")
        assertNotEquals(VpnColors.Green, protectionFailed, "замер НЕ подтвердил защиту — зелёный запрещён")
    }

    @Test
    fun `отключённое состояние нейтрально, а не красное`() {
        // Отключился — это не опасность, а покой. Красный обесценился бы.
        assertNotEquals(VpnColors.Red, ConnectionStatus.Disconnected.presentation().accent)
        assertNotEquals(VpnColors.Green, ConnectionStatus.Disconnected.presentation().accent)
    }

    @Test
    fun `сбой пробы и отрицательный замер объясняются по-разному`() {
        val unavailable = ConnectionStatus.ProtectionFailed(
            ProtectionVerdict.Failed(ProtectionFailure.ProbeUnavailable),
        ).presentation()
        val inconclusive = ConnectionStatus.ProtectionFailed(
            ProtectionVerdict.Failed(ProtectionFailure.Inconclusive),
        ).presentation()

        assertNotEquals(
            unavailable.detail,
            inconclusive.detail,
            "«не смогли проверить» и «проверили, и плохо» — разные сообщения",
        )
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
    fun `блок защиты отражает итог замера, а не факт поднятия туннеля`() {
        // «Проверять нечего» до подключения: туннеля ещё нет, и прочерки
        // притворялись бы проверкой. Это ключевая честность блока защиты.
        assertEquals(
            ProtectionBlockStatus.Unavailable,
            ConnectionStatus.Disconnected.presentation().protection.status,
        )
        // Confirmed выставляется ТОЛЬКО при состоявшемся положительном замере —
        // тот же инвариант, что и у зелёного цвета.
        assertEquals(
            ProtectionBlockStatus.Confirmed,
            protectedStatus().presentation().protection.status,
        )
        // Замер прошёл и НЕ подтвердил: это Failed, а не Checking и не NotChecked.
        // Если бы тут оказался Checking, пользователь ждал бы результата, которого
        // уже нет.
        assertEquals(
            ProtectionBlockStatus.Failed,
            failedStatus().presentation().protection.status,
        )
    }

    @Test
    fun `текст кнопки соответствует матрице действий`() {
        // Матрица из макета. Проверяем дословно, потому что неверная подпись
        // обещает действие, которого не будет.
        assertEquals("Подключить", ConnectionStatus.Disconnected.presentation().actionLabel)
        assertEquals("Отключить", protectedStatus().presentation().actionLabel)
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
