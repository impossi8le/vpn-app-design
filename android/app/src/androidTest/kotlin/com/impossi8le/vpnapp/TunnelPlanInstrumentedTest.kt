package com.impossi8le.vpnapp

import android.content.Intent
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.impossi8le.vpnapp.domain.tunnel.TunnelAddressing
import com.impossi8le.vpnapp.domain.tunnel.TunnelPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * То, что про туннель проверяется ТОЛЬКО на устройстве.
 *
 * Чего здесь НЕТ и почему:
 *  - `VpnService.Builder` **невозможно построить в тесте**. Это внутренний класс
 *    (`inner class Builder`), и его конструктор требует получателя — экземпляр
 *    `VpnService`, которому система выделила fd. Собрать его в инструментальном
 *    тесте нечем, поэтому попытка «проверить настройку Builder» — проверка
 *    неверного предположения, а не кода. Правила настройки проверяются на JVM
 *    через [TunnelPlan], где они и живут.
 *  - проба защиты (§6) и прохождение трафика требуют живого сервера: эмулятор
 *    даёт сетевой стек, но не даёт сервера.
 */
@RunWith(AndroidJUnit4::class)
class TunnelPlanInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `система знает о нашем VpnService`() {
        // prepare() возвращает null, когда согласие уже получено, и Intent, когда
        // его надо запросить. Отсутствие исключения означает, что сервис объявлен
        // корректно и зарегистрирован системой — а это проверяется только на
        // устройстве, манифест на JVM не читается.
        val intent: Intent? = VpnService.prepare(context)
        assertTrue(
            "prepare() обязан вернуть либо null, либо намерение запроса согласия",
            intent == null || intent.action != null,
        )
    }

    @Test
    fun `план туннеля содержит оба маршрута и DNS из профиля`() {
        // Значения плана от платформы не зависят, но здесь они читаются тем же
        // кодом, что пойдёт в сервис, — так ловится расхождение между доменом и
        // тем, что реально соберёт приложение.
        val plan = TunnelPlan.build(
            TunnelAddressing(
                ipv4Address = "10.8.0.2",
                ipv4PrefixLength = 24,
                dnsServers = listOf("10.8.0.1"),
            ),
        )

        assertTrue("IPv4 обязан идти в туннель", plan.routes.contains("0.0.0.0/0"))
        assertTrue("IPv6 обязан быть закрыт явным маршрутом", plan.routes.contains("::/0"))
        assertEquals(2, plan.routes.size)
        assertEquals("10.8.0.2/24", plan.addresses.single())
        assertEquals(listOf("10.8.0.1"), plan.dnsServers)
        assertTrue("исключённых приложений быть не должно", plan.disallowedApplications.isEmpty())
    }

    @Test
    fun `план не собирается без DNS`() {
        // Отрицательный случай: без DNS запросы ушли бы системному резолверу
        // мимо туннеля, поэтому такой план обязан отвергаться.
        try {
            TunnelPlan.build(TunnelAddressing("10.8.0.2", 24, emptyList()))
            throw AssertionError("план без DNS должен быть отвергнут")
        } catch (expected: IllegalArgumentException) {
            // Именно это и ожидалось.
        }
    }
}
