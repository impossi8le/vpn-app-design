package com.impossi8le.vpnapp.feature.account

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Подсказка про плитку должна быть честной и про удобство.
 *
 * Тест держит два обещания сразу.
 *
 * Первое — честность текста. Значка на панели по умолчанию НЕТ: его добавляет
 * сам пользователь. Вводная строка не оставляет места для прочтения «плитка уже
 * добавлена» — иначе экран обещал бы то, чего на устройстве нет.
 *
 * Второе — §6. Подсказка про плитку говорит об удобстве, а не о защите: в
 * текстах нет слов «защищено»/«безопасно» и нет обещаний, что трафик идёт через
 * туннель только потому, что значок нажат.
 *
 * Формулировки шагов намеренно обобщены: названия пунктов меню отличаются между
 * прошивками, и тест не требует конкретных слов «карандаш» или «изменить плитки»
 * как обязательных в каждом шаге.
 */
class HowToAddTileTextTest {

    private val texts = buildList {
        add(TILE_HOW_TO_INTRO)
        TILE_HOW_TO_STEPS.forEach { add(it.title); add(it.sub) }
        add(TILE_HOW_TO_NOTE)
    }

    @Test
    fun `вводная строка говорит, что плитки на панели пока нет`() {
        val intro = TILE_HOW_TO_INTRO.lowercase()

        assertTrue(intro.contains("добавить"), "должно быть сказано, что плитку добавляют")
        assertTrue(
            intro.contains("сам") || intro.contains("вручную"),
            "должно быть сказано, что добавить её нужно самому пользователю",
        )
        assertTrue(
            intro.contains("нет"),
            "должно быть прямо сказано, что на панели плитки пока нет",
        )
    }

    @Test
    fun `инструкция говорит про шторку быстрых настроек`() {
        val joined = texts.joinToString(" ").lowercase()

        assertTrue(joined.contains("быстрые настройки") || joined.contains("шторк"))
    }

    @Test
    fun `инструкция честно оговаривает, что названия пунктов различаются`() {
        assertTrue(TILE_HOW_TO_NOTE.lowercase().contains("отличаться"))
    }

    @Test
    fun `тексты не обещают защиту`() {
        // §6: удобство, а не защита. Ни одного обещания из этого списка.
        val forbidden = listOf("защищено", "защищен", "безопасно", "аноним")
        val joined = texts.joinToString(" ").lowercase()

        forbidden.forEach { word ->
            assertFalse(joined.contains(word), "подсказка про удобство не должна обещать «$word»")
        }
    }

    @Test
    fun `инструкция содержит последовательность шагов`() {
        // Четыре шага — минимально достаточный путь от шторки до перетаскивания.
        assertTrue(TILE_HOW_TO_STEPS.size >= 4)
        TILE_HOW_TO_STEPS.forEach { step ->
            assertTrue(step.title.isNotBlank())
            assertTrue(step.sub.isNotBlank())
        }
    }
}
