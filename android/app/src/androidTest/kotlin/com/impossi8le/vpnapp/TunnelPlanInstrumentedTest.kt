package com.impossi8le.vpnapp

import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.impossi8le.vpnapp.domain.tunnel.TunnelAddressing
import com.impossi8le.vpnapp.domain.tunnel.TunnelPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Единственное, что можно проверить про туннель БЕЗ поднятия настоящего
 * соединения: что план туннеля применим к `Builder` без ошибок и что система
 * знает о нашем VpnService.
 *
 * Чего здесь НЕТ и почему: проба защиты (§6) и реальное прохождение трафика
 * требуют туннеля к живому серверу. Эмулятор даёт сетевой стек, но не даёт
 * сервера, поэтому «зелёное не врёт» этим тестом не доказывается. Закрывается
 * только «конфигурация применяется без падения».
 *
 * ВАЖНО: `Builder.establish()` здесь НЕ вызывается. Он требует сервиса,
 * которому система выдаёт fd; у `Builder`, собранного вручную, такого сервиса
 * нет, и вызов падает с NPE. Проверка «establish не бросает» была бы проверкой
 * неверного предположения, а не кода.
 */
@RunWith(AndroidJUnit4::class)
class TunnelPlanInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `адреса, маршруты и DNS из плана принимаются без ошибок`() {
        val plan = TunnelPlan.build(
            TunnelAddressing(
                ipv4Address = "10.8.0.2",
                ipv4PrefixLength = 24,
                dnsServers = listOf("10.8.0.1"),
            ),
        )

        val builder = VpnService.Builder()
        // Каждый вызов ниже способен бросить при несовместимом адресе или маске.
        // Именно это и проверяется: план не приведёт к падению при настройке.
        plan.addresses.forEach { address ->
            builder.addAddress(address.substringBefore('/'), address.substringAfter('/').toInt())
        }
        plan.routes.forEach { route ->
            builder.addRoute(route.substringBefore('/'), route.substringAfter('/').toInt())
        }
        plan.dnsServers.forEach { builder.addDnsServer(it) }
        builder.setBlocking(plan.blocking)
        builder.setMtu(plan.mtu)

        assertNotNull(builder)
    }

    @Test
    fun `план содержит оба маршрута — IPv4 и закрытие IPv6`() {
        val plan = TunnelPlan.build(
            TunnelAddressing("10.8.0.2", 24, listOf("10.8.0.1")),
        )

        // Проверяем на устройстве то же, что и на JVM: состав маршрутов не
        // зависит от платформы, но здесь он идёт в тот же набор вызовов, что
        // использует реальный сервис.
        assertTrue(plan.routes.contains("0.0.0.0/0"))
        assertTrue(plan.routes.contains("::/0"))
        assertEquals(2, plan.routes.size)
    }

    @Test
    fun `система знает о нашем VpnService`() {
        // prepare() возвращает null, когда согласие уже получено, и Intent,
        // когда его надо запросить. Отсутствие исключения означает, что сервис
        // объявлен корректно и зарегистрирован системой.
        val intent = VpnService.prepare(context)
        assertTrue(
            "prepare() обязан вернуть либо null, либо намерение запроса согласия",
            intent == null || intent.action != null,
        )
    }
}
