package com.impossi8le.vpnapp.tunnel

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Чтение факта «туннель над трафиком есть» у системы.
 *
 * **Почему инструментальный, а не JVM.** `ConnectivityManager` существует только
 * на устройстве. А проверяет этот код то, ради чего сделана правка issue #32:
 * «Подключено» нельзя показывать, пока трафик идёт мимо туннеля.
 *
 * **Что здесь проверяется и чего проверить нельзя.** Без поднятого VPN проба
 * обязана ответить [TunnelPresence.ABSENT] — это ровно то состояние, в котором
 * раньше показывалось ложное «Подключено». Проверить `PRESENT` инструментально
 * нельзя: для этого нужно поднять настоящий VPN-туннель, а это делает
 * `:vpnservice` и требует согласия пользователя. Положительный случай закрыт
 * JVM-тестом чистой функции `confirmedByTunnelPresence`.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTunnelPresenceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun probe(): AndroidTunnelPresence {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return AndroidTunnelPresence(manager)
    }

    @Test
    fun безПоднятогоVpnФактТуннеляОтсутствует() {
        // Ключевая проверка правки: пока трафик идёт не через туннель, проба
        // обязана это сказать. На этом ответе экран показывает «Подключение»
        // вместо ложного «Подключено».
        val presence = probe().read()

        assertNotEquals(
            "проба подтвердила туннель там, где VPN не поднят",
            TunnelPresence.PRESENT,
            presence,
        )
        assertEquals(
            "без VPN активная сеть не VPN-сеть — это ровно ABSENT",
            TunnelPresence.ABSENT,
            presence,
        )
    }

    @Test
    fun чтениеФактаНеБросает() {
        // Проба обязана отвечать исходом, а не исключением: вызов из UI не
        // должен ронять экран из-за системного сбоя. Неизвестность — законный
        // ответ, падение — нет.
        repeat(3) { probe().read() }
    }
}
