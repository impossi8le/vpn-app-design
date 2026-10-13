package com.impossi8le.vpnapp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Правило «Назад» на корневом экране: первый жест — подсказка, второй — свернуть.
 *
 * Тест держит главное свойство: приложение не сворачивается по ОДИНОЧНОМУ
 * случайному жесту и не выглядит сломанным — оно всегда на что-то реагирует.
 */
class RootBackDecisionTest {

    private val window = 2_000L

    @Test
    fun `есть куда вернуться — всегда обычный возврат`() {
        // Даже если только что была подсказка: история важнее сворачивания.
        val d = decideBack(
            canNavigateBack = true,
            nowMs = 10_000L,
            lastMinimizeNoticeAtMs = 9_500L,
            windowMs = window,
        )
        assertEquals(BackDecision.NavigateBack, d)
    }

    @Test
    fun `первый жест на корневом экране показывает подсказку`() {
        val d = decideBack(
            canNavigateBack = false,
            nowMs = 10_000L,
            lastMinimizeNoticeAtMs = 0L,
            windowMs = window,
        )
        assertEquals(BackDecision.ShowMinimizeNotice, d)
    }

    @Test
    fun `повторный жест в пределах окна сворачивает`() {
        val d = decideBack(
            canNavigateBack = false,
            nowMs = 10_500L,
            lastMinimizeNoticeAtMs = 10_000L,
            windowMs = window,
        )
        assertEquals(BackDecision.Minimize, d)
    }

    @Test
    fun `жест после окна снова показывает подсказку, а не сворачивает`() {
        val d = decideBack(
            canNavigateBack = false,
            nowMs = 13_000L,
            lastMinimizeNoticeAtMs = 10_000L,
            windowMs = window,
        )
        assertEquals(BackDecision.ShowMinimizeNotice, d)
    }

    @Test
    fun `на самой границе окна жест ещё считается двойным`() {
        val d = decideBack(
            canNavigateBack = false,
            nowMs = 12_000L,
            lastMinimizeNoticeAtMs = 10_000L,
            windowMs = window,
        )
        assertEquals(BackDecision.Minimize, d)
    }

    @Test
    fun `часы пошли назад — жест считается первым, а не двойным`() {
        // Сработать мимо намерения хуже, чем не сработать: свернуть приложение
        // на жесте, который пользователь двойным не считает, нельзя.
        val d = decideBack(
            canNavigateBack = false,
            nowMs = 9_000L,
            lastMinimizeNoticeAtMs = 10_000L,
            windowMs = window,
        )
        assertEquals(BackDecision.ShowMinimizeNotice, d)
    }
}
