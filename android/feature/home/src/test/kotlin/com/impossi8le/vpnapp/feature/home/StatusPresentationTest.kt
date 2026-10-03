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
        assertEquals(VpnColors.Green, protected.accent)
        assertEquals("Защищено", protected.title)
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
        assertEquals(VpnColors.Red, failedStatus().presentation().accent)
        assertEquals(VpnColors.Red, ConnectionStatus.Failed("нет сети").presentation().accent)
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
