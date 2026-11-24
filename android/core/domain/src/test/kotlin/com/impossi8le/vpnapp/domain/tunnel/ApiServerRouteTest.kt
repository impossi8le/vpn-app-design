package com.impossi8le.vpnapp.domain.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Маршрут обхода для САМОГО API-сервера.
 *
 * Зачем это нужно: без исключения трафик приложения к его же API уходит в туннель.
 * Проверено на устройстве — при поднятом туннеле `curl` к нашему API висит до
 * таймаута, а с выключенным отвечает за 0.09 с. Следствие было тяжёлым и тихим:
 * ни метрики, ни отчёты об ошибках не доходили, потому что отправить их некуда —
 * запрос уходил внутрь туннеля и там пропадал.
 *
 * Разбор имени, а не хардкод адреса: `sslip.io` кодирует IP в самом имени
 * (`193-233-245-95` → `193.233.245.95`), поэтому при переезде сервера обновится
 * только `API_BASE_URL`, а исключение поедет за ним само. Хардкод пришлось бы
 * помнить и править в двух местах.
 */
class ApiServerRouteTest {

    @Test
    fun `sslip-имя даёт адрес хоста и префикс 32`() {
        val route = apiServerRouteOf("https://193-233-245-95.sslip.io/api/v1")

        assertEquals("193.233.245.95", route?.network)
        // /32 — ровно один адрес: исключаем только сам API, не задевая соседей.
        assertEquals(32, route?.prefixLength, "шире /32 увело бы мимо туннеля чужие адреса")
    }

    @Test
    fun `имя записи объясняет пользователю, что это за строка`() {
        val route = apiServerRouteOf("https://193-233-245-95.sslip.io/api/v1")

        assertEquals("Сервер приложения", route?.name)
    }

    @Test
    fun `без порта и пути тоже разбирается`() {
        val route = apiServerRouteOf("https://193-233-245-95.sslip.io")

        assertEquals("193.233.245.95", route?.network)
    }

    @Test
    fun `http тоже годится`() {
        val route = apiServerRouteOf("http://193-233-245-95.sslip.io:8080/api")

        assertEquals("193.233.245.95", route?.network)
    }

    @Test
    fun `обычный домен не даёт маршрута — выдумывать адрес нельзя`() {
        // Не sslip-форма: IP из имени не следует, а резолвить здесь негде —
        // функция чистая. Исключить неверный адрес хуже, чем не исключить вовсе.
        assertNull(apiServerRouteOf("https://vpnbotfree.ru/api/v1"))
    }

    @Test
    fun `битый базовый адрес не роняет разбор`() {
        assertNull(apiServerRouteOf(""))
        assertNull(apiServerRouteOf("не url"))
    }

    @Test
    fun `частично-sslip имя с лишними частями не принимается`() {
        // `a.193-233-245-95.sslip.io` кодирует НЕ тот IP, что в первых октетах;
        // принять его значило бы исключить чужой адрес.
        assertNull(apiServerRouteOf("https://a.193-233-245-95.sslip.io/api"))
    }

    @Test
    fun `октеты вне 0-255 не принимаются`() {
        assertNull(apiServerRouteOf("https://999-1-1-1.sslip.io/api"), "999 — не октет")
    }
}
