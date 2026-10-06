package com.impossi8le.vpnapp.domain.tunnel

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Чистая логика экрана «Обходы»: объединение и удаление подсетей, фильтр
 * каталога, разбор ручного ввода.
 *
 * Проверяется на JVM без Compose: это решения, а не разметка, и именно они
 * ломаются молча (регистр в поиске по русским названиям, «широкий» префикс,
 * дубликаты в списке, уходящем на сервер).
 */
class BypassPresentationTest {

    private fun service(key: String, title: String, vararg domains: String) =
        BypassService(key, title, domains.toList())

    private val vk = service("vk", "ВКонтакте", "vk.com", "vk.ru", "userapi.com")
    private val yandex = service("yandex", "Яндекс", "yandex.ru", "ya.ru")

    // --- Объединение и удаление ---

    @Test
    fun `объединение убирает дубликаты`() {
        val existing = listOf(BypassRoute("87.240.129.0", 24))
        val added = listOf(BypassRoute("87.240.129.0", 24), BypassRoute("77.88.0.0", 16))

        assertEquals(
            listOf(BypassRoute("77.88.0.0", 16), BypassRoute("87.240.129.0", 24)),
            mergeRoutes(existing, added),
        )
    }

    @Test
    fun `объединение детерминировано по сети и префиксу`() {
        // Порядок стабилен: список уходит на сервер и в файл, и сравнение
        // «до/после» должно быть осмысленным.
        val result = mergeRoutes(
            listOf(BypassRoute("10.0.0.0", 24), BypassRoute("10.0.0.0", 16)),
            listOf(BypassRoute("10.0.0.0", 8)),
        )
        assertEquals(
            listOf(
                BypassRoute("10.0.0.0", 8),
                BypassRoute("10.0.0.0", 16),
                BypassRoute("10.0.0.0", 24),
            ),
            result,
        )
    }

    @Test
    fun `удаление убирает ровно одну подсеть`() {
        val routes = listOf(BypassRoute("87.240.129.0", 24), BypassRoute("77.88.0.0", 16))
        assertEquals(
            listOf(BypassRoute("77.88.0.0", 16)),
            removeRoute(routes, BypassRoute("87.240.129.0", 24)),
        )
    }

    @Test
    fun `удаление отсутствующей подсети ничего не меняет`() {
        val routes = listOf(BypassRoute("87.240.129.0", 24))
        assertEquals(routes, removeRoute(routes, BypassRoute("1.2.3.0", 24)))
    }

    // --- Цели для «обойти все» ---

    @Test
    fun `цели собираются из всех сервисов без повторов и пустых`() {
        val targets = bypassTargets(
            listOf(
                vk,
                service("broken", "Пустой", "", "  ", "ya.ru"),
            ),
        )
        assertEquals(listOf("vk.com", "vk.ru", "userapi.com", "ya.ru"), targets)
    }

    // --- Разбор ручного ввода ---

    @Test
    fun `пустой ввод не уходит на сервер`() {
        assertEquals(CustomTarget.Blank, classifyCustomTarget(""))
        assertEquals(CustomTarget.Blank, classifyCustomTarget("   "))
    }

    @Test
    fun `домен принимается и уходит на сервер как есть`() {
        assertEquals(CustomTarget.Valid("vk.com"), classifyCustomTarget("  vk.com "))
    }

    @Test
    fun `корректная подсеть принимается`() {
        assertEquals(CustomTarget.Valid("87.240.132.0/24"), classifyCustomTarget("87.240.132.0/24"))
    }

    @Test
    fun `широкая подсеть отвергается локально, а не отправляется`() {
        // /0 и /1 — это «пусти мимо туннеля весь интернет». Такую запись нельзя
        // даже посылать: parseBypassCidr уже считает её мусором.
        assertEquals(CustomTarget.InvalidCidr, classifyCustomTarget("0.0.0.0/0"))
        assertEquals(CustomTarget.InvalidCidr, classifyCustomTarget("10.0.0.0/1"))
        assertEquals(CustomTarget.InvalidCidr, classifyCustomTarget("не-адрес/24"))
    }

    // --- Поиск ---

    @Test
    fun `пустой запрос возвращает весь каталог`() {
        assertEquals(listOf(vk, yandex), filterServices(listOf(vk, yandex), ""))
    }

    @Test
    fun `поиск по названию не зависит от регистра, включая русские буквы`() {
        // «вк» должно находить «ВКонтакте» — это и есть довод за lowercase(),
        // а не ASCII-хаки.
        assertEquals(listOf(vk), filterServices(listOf(vk, yandex), "вк"))
        assertEquals(listOf(vk), filterServices(listOf(vk, yandex), "ВКОНТАКТЕ"))
    }

    @Test
    fun `поиск находит по домену`() {
        assertEquals(listOf(vk), filterServices(listOf(vk, yandex), "userapi"))
        assertEquals(listOf(yandex), filterServices(listOf(vk, yandex), "YA.RU"))
    }

    @Test
    fun `поиск не находит чужих`() {
        assertTrue(filterServices(listOf(vk, yandex), "не-существует").isEmpty())
    }

    @Test
    fun `сводка считает сервисы и домены`() {
        assertEquals("Сервисов: 2, доменов: 5", catalogSummary(listOf(vk, yandex)))
    }

    @Test
    fun `сводка не пуста и без сервисов`() {
        assertFalse(catalogSummary(emptyList()).isEmpty())
    }
}
