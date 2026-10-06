package com.impossi8le.vpnapp.feature.home

import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionVerdict
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Подача главного экрана: только «подключено / нет» и какое подключение работает.
 *
 * Экран больше НЕ говорит о защите. Проба защиты — заглушка, поэтому тексты
 * «трафик не идёт через туннель» и «проверка не пройдена» ничего не измеряли.
 * Доменный инвариант §6 при этом не ослаблен: он живёт в `core:domain`
 * (`ProtectionGate`), а не в текстах. Здесь проверяется ровно то, о чём просил
 * пользователь: что интерфейс поднят и какой конфиг под ним.
 *
 * Красный по-прежнему означает только реальную опасность, а в состояниях
 * «туннель поднят» его нет: соединение не разорвано.
 */
class StatusPresentationTest {

    @Test
    fun `поднятый туннель показывается подключением без слова о защите`() {
        // Три доменных исхода «туннель поднят» — один и тот же текст.
        listOf(
            ConnectionStatus.VerifyingProtection,
            protectedStatus(),
            failedStatus(),
        ).forEach { status ->
            val presentation = status.presentation()
            assertEquals("Подключено", presentation.title, "для $status")
            assertTrue(
                !presentation.title.contains("защит", ignoreCase = true) &&
                    !presentation.detail.contains("защит", ignoreCase = true),
                "интерфейс не может утверждать защиту: проба её не подтверждает",
            )
        }
    }

    @Test
    fun `имя конфига показывается подзаголовком при поднятом туннеле`() {
        val presentation = ConnectionStatus.VerifyingProtection.presentation("Нидерланды")

        assertEquals("Подключено", presentation.title)
        assertEquals("Нидерланды", presentation.detail)
    }

    @Test
    fun `без имени конфига подзаголовок нейтрален`() {
        val presentation = ConnectionStatus.VerifyingProtection.presentation(null)

        assertEquals("Подключено", presentation.title)
        assertTrue(presentation.detail.isNotBlank())
        assertEquals("Соединение установлено", presentation.detail)
    }

    @Test
    fun `при активных обходах подробность говорит, что часть трафика идёт напрямую`() {
        // «Подключено» при обходах означает «не всё через туннель» — молчать об
        // этом значит повторять ту ложную уверенность, против которой §6.
        // bypassSupported задаём явно: дефолт теперь `false` (безопасная
        // сторона), а этот тест проверяет как раз случай работающего обхода.
        val p = ConnectionStatus.VerifyingProtection.presentation(
            runningConfigName = "Нидерланды",
            bypassCount = 6,
            bypassSupported = true,
            bypassConfigured = true,
        )

        assertEquals("Подключено", p.title)
        assertTrue(
            p.detail.contains("напрямую") || p.detail.contains("обход"),
            "подробность должна сказать о частичном обходе, было: ${p.detail}",
        )
    }

    @Test
    fun `без обходов подробность как прежде`() {
        val p = ConnectionStatus.VerifyingProtection.presentation("Нидерланды", bypassCount = 0)
        assertEquals("Нидерланды", p.detail)
    }

    @Test
    fun `при поддерживаемом обходе подробность говорит о части трафика напрямую`() {
        // API 33+: исключения применяются реально, поэтому «часть идёт напрямую» —
        // правда. Явно задаём bypassSupported, чтобы проба не опиралась на умолчание.
        val p = ConnectionStatus.VerifyingProtection.presentation(
            runningConfigName = "Нидерланды",
            bypassCount = 6,
            bypassSupported = true,
            bypassConfigured = true,
        )

        assertTrue(
            p.detail.contains("напрямую"),
            "обход работает — подробность обязана сказать о нём, было: ${p.detail}",
        )
    }

    @Test
    fun `при неподдерживаемом обходе подробность честно говорит о недоступности`() {
        // API 26–32: файл обходов лежит (bypassConfigured), но excludeRoute не
        // существует и маршруты НЕ исключаются — применено всегда 0. Сказать
        // «часть трафика идёт напрямую» здесь значило бы соврать: трафик целиком
        // идёт через туннель. Намерение и счётчик разведены нарочно: выводить
        // недоступность из разницы счётчиков нельзя — на API < 33 он всегда 0, и
        // ветка стала бы нераспознаваемой.
        val p = ConnectionStatus.VerifyingProtection.presentation(
            runningConfigName = "Нидерланды",
            bypassCount = 0,
            bypassSupported = false,
            bypassConfigured = true,
        )

        assertTrue(
            !p.detail.contains("напрямую"),
            "обход не применяется — нельзя утверждать, что трафик идёт напрямую: ${p.detail}",
        )

        // И не молчим: разница между «обходов нет» и «обход не работает здесь»
        // для пользователя существенна, поэтому текст обязан её озвучить. Эта
        // проверка — та самая регрессия: раньше ветка была недостижима и экран
        // молчал.
        assertTrue(
            p.detail.contains("недоступ", ignoreCase = true),
            "подробность обязана сказать, что обход недоступен, а не молчать: ${p.detail}",
        )
    }

    @Test
    fun `поддерживаемый обход с нулём применённых честно говорит о неиспользовании`() {
        // Устройство умеет исключать (API 33+), список непуст, но ни один
        // маршрут не применился (пусто после разбора, битые строки). Это не
        // успех и не обещание — текст обязан сказать «не применён», а не выдать
        // имя конфига за норму и не заявить о частичном обходе.
        val p = ConnectionStatus.VerifyingProtection.presentation(
            runningConfigName = "Нидерланды",
            bypassCount = 0,
            bypassSupported = true,
            bypassConfigured = true,
        )

        assertTrue(
            !p.detail.contains("напрямую"),
            "применённых обходов нет — нельзя утверждать частичный обход: ${p.detail}",
        )
        assertTrue(
            p.detail.contains("не применён") || p.detail.contains("не применен"),
            "подробность обязана сказать, что обход не применён: ${p.detail}",
        )
    }

    @Test
    fun `обходов не задумано — о них не упоминаем`() {
        // bypassConfigured == false: обходить нечего, и версия Android тут ни при
        // чём — текст остаётся прежним (имя конфига либо нейтральное «Соединение
        // установлено»), упоминания обхода появляться не должно. Так же ведёт
        // себя и неподдерживаемое устройство: без намерения молчим, а не пугаем
        // словом «недоступен».
        val named = ConnectionStatus.VerifyingProtection.presentation(
            runningConfigName = "Нидерланды",
            bypassCount = 0,
            bypassSupported = false,
        )
        assertEquals("Нидерланды", named.detail)

        val unnamed = ConnectionStatus.VerifyingProtection.presentation(
            runningConfigName = null,
            bypassCount = 0,
            bypassSupported = false,
        )
        assertEquals("Соединение установлено", unnamed.detail)
    }

    @Test
    fun `янтарный только у идущего процесса`() {
        assertEquals(VpnColors.Amber, ConnectionStatus.Connecting.presentation().accent)

        listOf(ConnectionStatus.Disconnected, ConnectionStatus.VerifyingProtection)
            .forEach {
                assertNotEquals(
                    VpnColors.Amber,
                    it.presentation().accent,
                    "янтарный означает процесс, а $it процессом не является",
                )
            }
    }

    @Test
    fun `переходное состояние подключения не читается как подключено`() {
        // Нажатие кнопки сразу даёт Connecting, а не VerifyingProtection. Пока
        // туннель не поднят, заголовок «Подключено» был бы ложью: он обещал бы
        // соединение, которого ещё нет. Заголовок обязан отличаться.
        assertNotEquals(
            "Подключено",
            ConnectionStatus.Connecting.presentation().title,
            "пока соединение устанавливается, «Подключено» показывать нельзя",
        )
    }

    @Test
    fun `красный только у реальной опасности`() {
        // Красный — сигнал разрыва, когда подключение НЕ состоялось. Проверяем
        // оба входа в Failed (нет сети и отказ ядра), чтобы правило держалось на
        // самом состоянии, а не на конкретной причине.
        assertEquals(VpnColors.Red, ConnectionStatus.Failed("нет сети").presentation().accent)
        assertEquals(VpnColors.Red, ConnectionStatus.Failed("отказ ядра").presentation().accent)

        // У поднятого туннеля разрыва нет — красный запрещён, иначе он обесценится
        // на настоящей опасности. Проверяем все три исхода «туннель поднят».
        listOf(
            ConnectionStatus.VerifyingProtection,
            protectedStatus(),
            failedStatus(),
        ).forEach {
            assertNotEquals(
                VpnColors.Red,
                it.presentation().accent,
                "разрыва нет — красный здесь кричал бы ложно ($it)",
            )
        }
    }

    @Test
    fun `отключённое состояние нейтрально, а не красное`() {
        // Отключился — это не опасность, а покой. Красный обесценился бы.
        assertNotEquals(VpnColors.Red, ConnectionStatus.Disconnected.presentation().accent)
    }

    @Test
    fun `у каждого состояния есть непустой текст`() {
        val all = listOf(
            ConnectionStatus.Disconnected,
            ConnectionStatus.Connecting,
            ConnectionStatus.VerifyingProtection,
            protectedStatus(),
            failedStatus(),
            ConnectionStatus.Failed("причина"),
        )
        all.forEach {
            val p = it.presentation()
            assertTrue(p.title.isNotBlank(), "заголовок пуст для $it")
            assertTrue(p.detail.isNotBlank(), "пояснение пусто для $it")
        }
    }

    @Test
    fun `причина сбоя подключения показывается пользователю`() {
        val presentation = ConnectionStatus.Failed("сервер недоступен").presentation()
        assertTrue(presentation.detail.contains("сервер недоступен"))
    }

    @Test
    fun `текст кнопки соответствует матрице действий`() {
        // Матрица из макета. Проверяем дословно, потому что неверная подпись
        // обещает действие, которого не будет.
        assertEquals("Подключить", ConnectionStatus.Disconnected.presentation().actionLabel)
        assertEquals("Отключить", protectedStatus().presentation().actionLabel)
        assertEquals("Отключить", ConnectionStatus.VerifyingProtection.presentation().actionLabel)
        assertEquals("Повторить", ConnectionStatus.Failed("нет сети").presentation().actionLabel)

        val connecting = ConnectionStatus.Connecting.presentation()
        // В подключении кнопка ОТМЕНЯЕТ попытку, а не отключает: отключать ещё
        // нечего, туннель не поднят. Подпись «Отключить» здесь врала бы.
        assertEquals("Отменить", connecting.actionLabel)
        assertEquals(
            StatusAction.Cancel,
            connecting.action,
            "отмена отличается от отключения: отключать нечего",
        )
    }

    // Вспомогательные: собрать Protected и ProtectionFailed можно только теми
    // путями, которые предусмотрены доменом.
    private fun protectedStatus(): ConnectionStatus =
        ConnectionStatus.Protected(
            (ProtectionVerdict.evaluate(true, true, true) as ProtectionVerdict.Confirmed).evidence,
        )

    private fun failedStatus(): ConnectionStatus =
        ConnectionStatus.ProtectionFailed(
            // Замер состоялся, но признак не подтвердился: маршрут IPv6 не закрыт.
            ProtectionVerdict.evaluate(ipv4InTunnel = false, ipv6Closed = true, dnsInside = true)
                as ProtectionVerdict.Failed,
        )
}
