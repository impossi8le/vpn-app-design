package com.impossi8le.vpnapp

import android.content.Intent
import android.net.VpnService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.impossi8le.vpnapp.domain.tunnel.TunnelAddressing
import com.impossi8le.vpnapp.domain.tunnel.TunnelPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * То, что про туннель проверяется ТОЛЬКО на устройстве.
 *
 * ИМЕНА МЕТОДОВ ЗДЕСЬ БЕЗ ПРОБЕЛОВ И БЕЗ ОБРАТНЫХ КАВЫЧЕК, и это не стиль.
 * Инструментальные тесты компилируются в DEX, а D8 до версии 040 запрещает
 * пробелы в именах методов: «Space characters in SimpleName ... are not allowed
 * prior to DEX version 040». На JVM обратные кавычки работают, здесь — ломают
 * сборку. Поэтому в `src/test` имена описательные, а в `src/androidTest` — camelCase.
 *
 * Чего здесь НЕТ и почему:
 *  - `VpnService.Builder` **невозможно построить в тесте**. Это внутренний класс
 *    (`inner class Builder`), его конструктор требует получателя — экземпляр
 *    `VpnService`, которому система выделила fd. Собрать его нечем, поэтому
 *    «проверка настройки Builder» была бы проверкой неверного предположения.
 *    Правила настройки живут в [TunnelPlan] и проверяются на JVM.
 *  - проба защиты (§6) и прохождение трафика требуют живого сервера: эмулятор
 *    даёт сетевой стек, но не даёт сервера.
 */
@RunWith(AndroidJUnit4::class)
class TunnelPlanInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun systemKnowsAboutOurVpnService() {
        // Смысл проверки: система ДОЛЖНА знать о нашем VpnService. Если сервис
        // объявлен неверно (нет разрешения BIND_VPN_SERVICE, не тот тег,
        // опечатка в имени класса), prepare() падает с IllegalStateException.
        //
        // Что здесь НЕ проверяется и почему: содержимое намерения. На чистом
        // эмуляторе prepare() возвращает не-null Intent с ПУСТЫМ action — это
        // нормальное поведение системы, а не признак поломки. Раньше тест
        // утверждал `intent == null || intent.action != null` и падал именно на
        // этом: проверялось неверное предположение об API, а не код.
        val intent: Intent? = VpnService.prepare(context)

        // Достаточно того, что вызов завершился без исключения: это и означает,
        // что сервис зарегистрирован системой.
        if (intent != null) {
            // Если система просит согласие — намерение должно быть запускаемым.
            assertTrue(
                "намерение запроса согласия должно разрешаться хоть куда-то",
                intent.resolveActivity(context.packageManager) != null,
            )
        }
    }

    @Test
    fun tunnelPlanCarriesBothRoutesAndProfileDns() {
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
    fun planIsRejectedWithoutDns() {
        // Отрицательный случай: без DNS запросы ушли бы системному резолверу
        // мимо туннеля, поэтому такой план обязан отвергаться.
        try {
            TunnelPlan.build(TunnelAddressing("10.8.0.2", 24, emptyList()))
            fail("план без DNS должен быть отвергнут")
        } catch (expected: IllegalArgumentException) {
            // Именно это и ожидалось.
        }
    }
}
