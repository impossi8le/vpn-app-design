package com.impossi8le.vpnapp.vpnengine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Ловушка на подмену разрыва подъёмом.
 *
 * Дефект, который этот тест закрывает, был на устройстве: `DISCONNECTED`
 * содержит `CONNECTED` как подстроку, поэтому разбор через `contains` превращал
 * разрыв в «туннель поднят». После отключения экран возвращался к «Трафик не
 * идёт через туннель … Отключить», пользователь жал второй раз — и это
 * поднимало новый туннель. Тест проверяет, что имена различаются ТОЧНО.
 */
class EngineEventTest {

    @Test
    fun `DISCONNECTED не путается с CONNECTED несмотря на подстроку`() {
        // Предохранитель от «исправления» на contains: свойство строки, из-за
        // которого дефект и существовал. Если он когда-нибудь перестанет быть
        // истинным, тест скажет об этом явно.
        assertEquals(true, "DISCONNECTED".contains("CONNECTED"))

        assertEquals(EngineState.Disconnected, engineStateFrom("DISCONNECTED"))
    }

    @Test
    fun `CONNECTED даёт Connected`() {
        assertEquals(EngineState.Connected, engineStateFrom("CONNECTED"))
    }

    @Test
    fun `RECONNECTING даёт Reconnecting`() {
        assertEquals(EngineState.Reconnecting, engineStateFrom("RECONNECTING"))
    }

    @Test
    fun `промежуточные шаги не дают состояния`() {
        // Для этих имён своего состояния нет: важно «идёт процесс», а не фаза.
        // Незнакомое имя безопаснее молчания, чем «подключено».
        for (name in listOf("CONNECTING", "GET_CONFIG", "ASSIGN_IP", "ADD_ROUTES", "RESOLVE", "WAIT")) {
            assertNull(engineStateFrom(name), "«$name» не должно давать состояния")
        }
    }
}
