package com.impossi8le.vpnapp

import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.impossi8le.vpnapp.domain.tunnel.TunnelAddressing
import com.impossi8le.vpnapp.domain.tunnel.TunnelPlan
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Единственное, что можно проверить про туннель БЕЗ поднятия настоящего
 * соединения: что `Builder` принимает построенный план и что подготовка
 * сервиса вообще возможна.
 *
 * Чего здесь НЕТ и почему: проба защиты (§6) и реальное прохождение трафика
 * требуют поднятого туннеля к живому серверу. Эмулятор даёт сетевой стек, но не
 * даёт сервера, поэтому «зелёное не врёт» этим тестом не доказывается —
 * закрывается только «конфигурация применяется без падения».
 */
@RunWith(AndroidJUnit4::class)
class TunnelPlanInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `Builder принимает построенный план`() {
        val plan = TunnelPlan.build(
            TunnelAddressing(
                ipv4Address = "10.8.0.2",
                ipv4PrefixLength = 24,
                dnsServers = listOf("10.8.0.1"),
            ),
        )

        val builder = VpnService.Builder()
        plan.addresses.forEach { builder.addAddress(it.substringBefore('/'), it.substringAfter('/').toInt()) }
        plan.routes.forEach { builder.addRoute(it.substringBefore('/'), it.substringAfter('/').toInt()) }
        plan.dnsServers.forEach { builder.addDnsServer(it) }
        builder.setBlocking(plan.blocking)
        builder.setMtu(plan.mtu)

        // Сборка не должна бросить: несовместимый адрес или маска падают здесь,
        // а не на устройстве пользователя.
        assertNotNull(builder.establish())
    }

    @Test
    fun `подготовка VpnService доступна на устройстве`() {
        // VpnService.prepare() возвращает null, когда согласие уже получено,
        // и Intent, когда его надо запросить. Отсутствие исключения означает,
        // что сервис объявлен корректно и система о нём знает.
        val intent = VpnService.prepare(context)
        assertTrue(
            "prepare() обязан вернуть либо null, либо намерение запроса согласия",
            intent == null || intent.action != null,
        )
    }
}
