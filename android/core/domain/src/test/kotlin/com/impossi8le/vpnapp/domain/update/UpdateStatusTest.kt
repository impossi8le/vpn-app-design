package com.impossi8le.vpnapp.domain.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class UpdateStatusTest {

    @ParameterizedTest
    @CsvSource(
        "android-v12, 12",
        "android-v1, 1",
        "android-v4096, 4096",
    )
    fun `тег релиза даёт номер сборки`(tag: String, expected: Int) {
        assertEquals(expected, parseReleaseTag(tag))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "v", "android-v", "android-vX", "v1.0.5", "12", "android 12"])
    fun `чужой или битый тег не даёт номера`(tag: String) {
        assertNull(parseReleaseTag(tag), "«$tag» — не наш тег, номер сборки неизвестен")
    }

    @Test
    fun `версия новее — доступно обновление`() {
        assertEquals(
            UpdateStatus.Available(13),
            updateStatus(currentVersionCode = 12, latestTag = "android-v13"),
        )
    }

    @Test
    fun `та же версия — обновление не нужно`() {
        assertEquals(
            UpdateStatus.UpToDate,
            updateStatus(currentVersionCode = 12, latestTag = "android-v12"),
        )
    }

    @Test
    fun `старая версия в релизе не тянет назад`() {
        assertEquals(
            UpdateStatus.UpToDate,
            updateStatus(currentVersionCode = 12, latestTag = "android-v11"),
        )
    }

    @Test
    fun `неизвестный тег — не знаем, а не да`() {
        assertEquals(UpdateStatus.Unknown, updateStatus(currentVersionCode = 12, latestTag = "v1.0.5"))
        assertEquals(UpdateStatus.Unknown, updateStatus(currentVersionCode = 12, latestTag = null))
    }

    @Test
    fun `порог читается из текста файла`() {
        assertEquals(90, parseMinSupported("90"))
        // Файл часто заканчивается переводом строки — это не ошибка.
        assertEquals(90, parseMinSupported("90\n"))
        assertEquals(7, parseMinSupported("  7  "))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "   ", "ninety", "90a", "-5", "9.0"])
    fun `битый порог не даёт числа`(text: String) {
        assertNull(parseMinSupported(text), "«$text» — это не порог")
    }

    @Test
    fun `версия ниже порога не поддерживается`() {
        assertEquals(false, isVersionSupported(currentVersionCode = 89, minSupported = 90))
        assertEquals(false, isVersionSupported(currentVersionCode = 1, minSupported = 90))
    }

    @Test
    fun `версия на пороге и выше поддерживается`() {
        assertEquals(true, isVersionSupported(currentVersionCode = 90, minSupported = 90))
        assertEquals(true, isVersionSupported(currentVersionCode = 91, minSupported = 90))
    }

    @Test
    fun `неизвестный порог не блокирует`() {
        // Иначе недоступный файл запер бы человека вне оплаченного приложения.
        assertEquals(true, isVersionSupported(currentVersionCode = 1, minSupported = null))
    }
}
