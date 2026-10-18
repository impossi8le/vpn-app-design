package com.impossi8le.vpnapp.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Индикатор состояния: то, что реально нарисовано на экране.
 *
 * ИМЕНА МЕТОДОВ БЕЗ ПРОБЕЛОВ И ОБРАТНЫХ КАВЫЧЕК: инструментальные тесты идут в
 * DEX, а D8 до версии 040 запрещает пробелы в именах. На JVM обратные кавычки
 * работают, здесь ломают сборку.
 *
 * **Главный инвариант проекта (§6).** Системный флаг «туннель поднят» не
 * доказывает, что трафик защищён. В Android-сборке проба защиты — заглушка,
 * поэтому «подключено» НИКОГДА не должно выглядеть как «защищено». Решение о
 * тоне принимает `presentation()` в `feature/home` и оно уже проверено на JVM;
 * здесь проверяется вторая половина — что сама отрисовка не подмешивает зелёный
 * от себя. Зелёный на экране подключения — это обещание защиты, которого клиент
 * дать не может, и увидеть его можно только на отрендеренных пикселях.
 *
 * По той же причине проверяется и обратное: зелёный ПОЯВЛЯЕТСЯ, когда
 * вызывающий передал [RingTone.Protected]. Без этого теста «нет зелёного» можно
 * было бы выполнить, просто убрав цвет из компонента, — и настоящая защита
 * перестала бы отличаться от её отсутствия.
 */
class StatusIndicatorTest {

    @get:Rule
    val compose = createComposeRule()

    private fun renderRings(tone: RingTone) {
        compose.setContent {
            StatusRings(
                tone = tone,
                modifier = Modifier.background(VpnColors.Void),
                testTag = RINGS_TAG,
            )
        }
    }

    // --- Ложная защищённость: зелёного быть не должно -------------------------

    @Test
    fun verifyingTonePaintsNoGreen() {
        // Туннель УЖЕ поднят, но защита не подтверждена — это состояние, в
        // котором ложный зелёный и появился бы. Кольца обязаны быть нейтральными.
        renderRings(RingTone.Verifying)

        val pixels = compose.onNodeWithTag(RINGS_TAG).capturedPixels()

        assertTrue("индикатор не нарисовался вовсе", pixels.countPainted() > 0)
        assertEquals(
            "состояние «туннель поднят, защита не измерена» нарисовано зелёным",
            0,
            pixels.countPixels { it.isGreen() },
        )
    }

    @Test
    fun idleTonePaintsNoGreen() {
        // Выключено — это нейтральное состояние, а не ошибка и не защита.
        renderRings(RingTone.Idle)

        val pixels = compose.onNodeWithTag(RINGS_TAG).capturedPixels()

        assertTrue(pixels.countPainted() > 0)
        assertEquals(0, pixels.countPixels { it.isGreen() })
    }

    @Test
    fun processTonePaintsNoGreen() {
        // Идёт подключение: работа идёт, результата ещё нет.
        renderRings(RingTone.Process)

        val pixels = compose.onNodeWithTag(RINGS_TAG).capturedPixels()

        assertTrue(pixels.countPainted() > 0)
        assertEquals(0, pixels.countPixels { it.isGreen() })
    }

    @Test
    fun processTonePaintsAmber() {
        // Обратная сторона: «идёт процесс» обязан читаться янтарным, иначе
        // тест «нет зелёного» проходил бы и на полностью бесцветном индикаторе.
        renderRings(RingTone.Process)

        val pixels = compose.onNodeWithTag(RINGS_TAG).capturedPixels()

        assertTrue(
            "идущий процесс не показан янтарным",
            pixels.anyPixel { it.isAmber() },
        )
    }

    @Test
    fun attentionTonePaintsNeitherGreenNorRed() {
        // Замер состоялся и НЕ подтвердил защиту. Соединение не разорвано, и
        // пугать красным не за что: это спокойное «вы не защищены», а не авария.
        renderRings(RingTone.Attention)

        val pixels = compose.onNodeWithTag(RINGS_TAG).capturedPixels()

        assertEquals(0, pixels.countPixels { it.isGreen() })
        assertEquals(
            "несостоявшийся замер показан аварийным красным",
            0,
            pixels.countPixels { it.isRed() },
        )
    }

    @Test
    fun dangerTonePaintsRed() {
        // Красный — только для реальной опасности, и там он обязан быть виден.
        renderRings(RingTone.Danger)

        val pixels = compose.onNodeWithTag(RINGS_TAG).capturedPixels()

        assertTrue("реальная опасность не показана красным", pixels.anyPixel { it.isRed() })
        assertEquals(0, pixels.countPixels { it.isGreen() })
    }

    @Test
    fun onlyProtectedTonePaintsGreen() {
        // Перебор всех тонов: зелёный — единственное состояние с подтверждённой
        // защитой. Если кто-то закрасит зелёным ещё один тон, тест назовёт его.
        // `setContent` можно вызвать только один раз, поэтому тон меняется
        // состоянием, а не повторной установкой содержимого.
        var tone by mutableStateOf(RingTone.Protected)
        compose.setContent {
            StatusRings(tone = tone, modifier = Modifier.background(VpnColors.Void), testTag = RINGS_TAG)
        }

        val greenTones = RingTone.entries.filter { candidate ->
            tone = candidate
            compose.waitForIdle()
            compose.onNodeWithTag(RINGS_TAG).capturedPixels().anyPixel { it.isGreen() }
        }

        assertEquals(
            "зелёный должен быть только у подтверждённой защиты",
            listOf(RingTone.Protected),
            greenTones,
        )
    }

    // --- Заголовок и пояснение ----------------------------------------------

    @Test
    fun headlineRendersTitleDetailAndHint() {
        compose.setContent {
            StatusHeadline(
                title = "Подключено",
                detail = "Соединение установлено",
                accent = VpnColors.Mist,
                hint = "Можно пользоваться",
                titleTestTag = HEADLINE_TITLE_TAG,
                detailTestTag = HEADLINE_DETAIL_TAG,
                hintTestTag = HEADLINE_HINT_TAG,
            )
        }

        compose.onNodeWithTag(HEADLINE_TITLE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Подключено").assertIsDisplayed()
        compose.onNodeWithText("Соединение установлено").assertIsDisplayed()
        compose.onNodeWithText("Можно пользоваться").assertIsDisplayed()
    }

    @Test
    fun headlineWithoutHintDoesNotInventAWaitingLine() {
        // `hint == null` — ждать нечего, и строки нет. Пустая строка ожидания
        // читалась бы как незавершённость: «что-то ещё идёт».
        compose.setContent {
            StatusHeadline(
                title = "Не подключено",
                detail = "Выберите подключение",
                accent = VpnColors.TextSecondary,
                hint = null,
                hintTestTag = HEADLINE_HINT_TAG,
            )
        }

        compose.onNodeWithTag(HEADLINE_HINT_TAG).assertDoesNotExist()
    }

    @Test
    fun headlineNeutralAccentPaintsNoGreen() {
        // Заголовок «Подключено» рисуется переданным акцентом. В презентации это
        // Mist — нейтральный. Проверяем пиксели, а не аргумент: захардкоженный
        // зелёный в разметке прошёл бы мимо решения `presentation()`.
        compose.setContent {
            StatusHeadline(
                title = "Подключено",
                detail = "Соединение установлено",
                accent = VpnColors.Mist,
                titleTestTag = HEADLINE_TITLE_TAG,
            )
        }

        val pixels = compose.onNodeWithTag(HEADLINE_TITLE_TAG).capturedPixels()

        assertTrue("заголовок не нарисовался", pixels.countPainted() > 0)
        assertEquals(
            "«Подключено» нарисовано зелёным — это обещание защиты",
            0,
            pixels.countPixels { it.isGreen() },
        )
    }

    @Test
    fun headlineHonoursTheAccentItWasGiven() {
        // Обратная проверка к предыдущей: цвет заголовка следует за аргументом,
        // а не является константой. Красный здесь — доказательство, что акцент
        // действительно доходит до отрисовки.
        compose.setContent {
            StatusHeadline(
                title = "Не удалось подключиться",
                detail = "Сервер недоступен",
                accent = VpnColors.Red,
                titleTestTag = HEADLINE_TITLE_TAG,
            )
        }

        val pixels = compose.onNodeWithTag(HEADLINE_TITLE_TAG).capturedPixels()

        assertTrue("акцент заголовка не доходит до отрисовки", pixels.anyPixel { it.isRed() })
    }

    @Test
    fun headlineCentresBothLinesWithoutOverlap() {
        // Заголовок и пояснение — две отдельные строки в колонке. Слитые в одну
        // (или наложенные) они не читаются как «состояние + куда идёт трафик».
        compose.setContent {
            Column(modifier = Modifier.background(VpnColors.Void)) {
                StatusHeadline(
                    title = "Подключено",
                    detail = "Соединение установлено",
                    accent = VpnColors.Mist,
                    titleTestTag = HEADLINE_TITLE_TAG,
                    detailTestTag = HEADLINE_DETAIL_TAG,
                )
            }
        }

        val title = compose.onNodeWithTag(HEADLINE_TITLE_TAG).fetchSemanticsNode().boundsInRoot
        val detail = compose.onNodeWithTag(HEADLINE_DETAIL_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue(
            "заголовок и пояснение наложились друг на друга",
            detail.top >= title.bottom,
        )
    }

    @Test
    fun ringsAreNotInteractive() {
        // Кольца неинтерактивны намеренно: в первой версии макета они выглядели
        // нажимаемыми, и человек пытался «включить VPN» тапом по картинке.
        // Если кто-то добавит сюда clickable, тест это заметит.
        var taps = 0
        compose.setContent {
            StatusRings(tone = RingTone.Idle, modifier = Modifier, testTag = RINGS_TAG)
            // Для теста нужен хоть один кликабельный узел рядом, иначе сам факт
            // «тап не сработал» ничего не доказывает — он не сработал бы везде.
            VpnPrimaryButton(text = "Подключить", onClick = { taps++ }, testTag = BUTTON_TAG)
        }

        compose.onNodeWithTag(RINGS_TAG).performClick()
        assertEquals("тап по кольцам не должен вызывать действие", 0, taps)

        compose.onNodeWithTag(BUTTON_TAG).performClick()
        assertEquals("проверка тапа нерабочая: кнопка рядом тоже не нажалась", 1, taps)
    }

    @Test
    fun everyRingTonePaintsSomething() {
        // Ни один тон не должен рисоваться в пустоту: «кольца не видно» — это
        // тоже ложь о состоянии, только молчаливая.
        var tone by mutableStateOf(RingTone.Idle)
        compose.setContent {
            StatusRings(tone = tone, modifier = Modifier.background(VpnColors.Void), testTag = RINGS_TAG)
        }

        RingTone.entries.forEach { candidate ->
            tone = candidate
            compose.waitForIdle()
            val visible = compose.onNodeWithTag(RINGS_TAG).capturedPixels().countPainted()
            assertTrue("тон $candidate нарисовался пустым", visible > 0)
        }
    }

    @Test
    fun accentColorIsNotUsedAsAStateIndicator() {
        // Ice — акцент выделения и фокуса, не индикатор состояния. Кольца ни при
        // каком тоне не должны рисоваться им: иначе фокус читался бы как защита.
        var tone by mutableStateOf(RingTone.Idle)
        compose.setContent {
            StatusRings(tone = tone, modifier = Modifier.background(VpnColors.Void), testTag = RINGS_TAG)
        }

        RingTone.entries.forEach { candidate ->
            tone = candidate
            compose.waitForIdle()
            val pixels = compose.onNodeWithTag(RINGS_TAG).capturedPixels()
            assertEquals(
                "тон $candidate использует акцент выделения как индикатор состояния",
                0,
                pixels.countPixels { it.isAccentBlue() },
            )
        }
    }


    private companion object {
        const val RINGS_TAG = "status_rings"
        const val HEADLINE_TITLE_TAG = "headline_title"
        const val HEADLINE_DETAIL_TAG = "headline_detail"
        const val HEADLINE_HINT_TAG = "headline_hint"
        const val BUTTON_TAG = "headline_button"
    }
}
