package com.impossi8le.vpnapp.feature.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Пороги эскалации ожидания из макета («15. Эскалация ожидания»): 10 / 20 / 30
 * секунд. Ошибка на единицу в границе («на 20-й секунде показали смену страны
 * вместо пояснения») глазами не ловится и меняет поведение — поэтому границы
 * проверяются дословно по каждой секунде, а не размазанным диапазоном.
 *
 * Стадии по макету:
 * - < 10 с — обычное «Подключение»;
 * - 10..20 с — пояснение «сервер отвечает медленно» (ровно 10 и ровно 20 здесь);
 * - > 20 с — нейтральное предложение других стран;
 * - > 30 с — уже НЕ «идёт подключение», а честная ошибка с выходом.
 */
class EscalationTest {

    /**
     * Главный тест: таблица секунда -> ожидаемая стадия. Каждая строка — граница,
     * где раньше всего проявляется сдвиг порога на единицу. Если кто-то поменяет
     * `>=` на `>` или `20` на `21`, сломается конкретная строка этой таблицы.
     */
    @Test
    fun `каждая граница даёт стадию, задуманную макетом`() {
        val expected = mapOf(
            0 to EscalationStage.Normal,
            9 to EscalationStage.Normal,
            10 to EscalationStage.SlowServer,
            19 to EscalationStage.SlowServer,
            20 to EscalationStage.SlowServer,
            21 to EscalationStage.SuggestAlternative,
            29 to EscalationStage.SuggestAlternative,
            30 to EscalationStage.SuggestAlternative,
            31 to EscalationStage.Failed,
            60 to EscalationStage.Failed,
        )
        expected.forEach { (seconds, stage) ->
            assertEquals(
                stage,
                escalationStage(seconds),
                "на $seconds секундах ожидается $stage",
            )
        }
    }

    /**
     * Ровно 10 секунд — ещё пояснение о медленном сервере, а не предложение
     * сменить страну: граница входит в предыдущую стадию. Это тот случай, который
     * обычно и путают (`>` вместо `>=`).
     */
    @Test
    fun `ровно 10 секунд это ещё медленный сервер, а не смена страны`() {
        assertEquals(EscalationStage.SlowServer, escalationStage(10))
        assertNotEquals(EscalationStage.SuggestAlternative, escalationStage(10))
    }

    /**
     * Ровно 20 секунд и ровно 30 секунд остаются в ПРЕДЫДУЩЕЙ стадии: порог
     * срабатывает на 21 и 31 соответственно. Иначе уже в первую лишнюю секунду
     * пользователю показывали бы следующий, более тревожный шаг.
     */
    @Test
    fun `пороги срабатывают строго после 20 и после 30`() {
        assertEquals(EscalationStage.SlowServer, escalationStage(20))
        assertEquals(EscalationStage.SuggestAlternative, escalationStage(21))

        assertEquals(EscalationStage.SuggestAlternative, escalationStage(30))
        assertEquals(EscalationStage.Failed, escalationStage(31))
    }

    /**
     * Дольше 30 секунд — это уже НЕ «идёт подключение», а честная ошибка. Заголовок
     * обязан это отражать: продолжать писать «Подключение» значило бы врать, что
     * шанс ещё есть.
     */
    @Test
    fun `после 30 секунд заголовок перестаёт обещать подключение`() {
        listOf(31, 60).forEach { seconds ->
            val stage = escalationStage(seconds)
            assertEquals(EscalationStage.Failed, stage, "на $seconds с это ошибка, а не процесс")

            val presentation = escalationPresentation(stage, "Финляндия", seconds)
            assertNotEquals(
                "Подключение",
                presentation.title,
                "«Подключение» на $seconds с обещает процесс, которого нет",
            )
        }
    }

    /**
     * На ошибке (`Failed`) пользователю обязан быть предложен выход — «Повторить»
     * и «Выбрать другое». Без [EscalationPresentation.showExits] он остаётся ждать
     * там, где ждать уже нечего.
     */
    @Test
    fun `на 31 и 60 секундах пользователю предложен выход`() {
        listOf(31, 60).forEach { seconds ->
            val presentation =
                escalationPresentation(escalationStage(seconds), "Финляндия", seconds)
            assertTrue(
                presentation.showExits,
                "на $seconds с должны быть доступны «Повторить» и «Выбрать другое»",
            )
        }
    }

    /**
     * До 30 секунд включительно выходы НЕ показываются: предлагать «Выбрать
     * другое» во время нормального, просто небыстрого подключения — значит
     * подгонять пользователя бросить попытку раньше времени.
     */
    @Test
    fun `до 30 секунд выходы не показываются`() {
        listOf(0, 10, 20, 30).forEach { seconds ->
            assertFalse(
                escalationPresentation(escalationStage(seconds), "Финляндия", seconds).showExits,
                "на $seconds с ещё рано предлагать выход",
            )
        }
    }

    /**
     * До 30 секунд тревога остаётся пояснением (нейтральный тон), а не ошибкой:
     * янтарный и красный в статусе, где идёт нормальный процесс, обесценили бы
     * настоящую опасность.
     */
    @Test
    fun `до 30 секунд тон пояснений нейтральный`() {
        listOf(EscalationStage.Normal, EscalationStage.SlowServer, EscalationStage.SuggestAlternative)
            .forEach { stage ->
                assertEquals(
                    com.impossi8le.vpnapp.core.ui.Tone.Neutral,
                    escalationPresentation(stage, "Финляндия", 15).noticeTone,
                    "тон $stage должен быть нейтральным, а не тревожным",
                )
            }
    }

    /**
     * Отрицательное время не должно ломать функцию (не бросать исключение): это
     * краевой вход от растущего счётчика в момент сброса/старта. Трактуем как
     * «ещё ничего не прошло» — [EscalationStage.Normal].
     */
    @Test
    fun `отрицательное время не бросает исключение и трактуется как начало`() {
        assertEquals(EscalationStage.Normal, escalationStage(-1))
        assertEquals(EscalationStage.Normal, escalationStage(Int.MIN_VALUE))
    }

    /**
     * Монотонность: стадия не может «упасть назад» с ростом времени. Проверяем на
     * всём диапазоне 0..40 — скачок назад означал бы, что пользователь на более
     * поздней секунде видит менее тревожное состояние, и эскалация врёт.
     */
    @Test
    fun `стадия монотонно не убывает на диапазоне 0 до 40`() {
        var previous = escalationStage(0)
        for (seconds in 1..40) {
            val current = escalationStage(seconds)
            assertTrue(
                current.ordinal >= previous.ordinal,
                "на $seconds с стадия откатилась с $previous до $current",
            )
            previous = current
        }
    }

    /**
     * Большие значения не переполняют стадию: на больших секундах стадия остаётся
     * Failed, а не заворачивается в Normal из-за арифметики.
     */
    @Test
    fun `большие значения остаются ошибкой`() {
        assertEquals(EscalationStage.Failed, escalationStage(Int.MAX_VALUE))
    }
}
