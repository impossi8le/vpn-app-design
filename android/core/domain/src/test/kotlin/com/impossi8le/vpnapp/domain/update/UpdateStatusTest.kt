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
}
