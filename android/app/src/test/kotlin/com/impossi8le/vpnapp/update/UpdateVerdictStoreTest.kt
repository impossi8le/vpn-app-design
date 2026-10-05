package com.impossi8le.vpnapp.update

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class UpdateVerdictStoreTest {

    @TempDir
    lateinit var dir: File

    private fun store() = UpdateVerdictStore(File(dir, "update-verdict.txt"))

    @Test
    fun `вердикт переживает перезапуск`() {
        store().write(latest = 95, minSupported = 90)

        // Новый экземпляр — как новый запуск приложения.
        assertEquals(
            UpdateVerdictStore.Verdict(latest = 95, minSupported = 90),
            store().read(),
        )
    }

    @Test
    fun `неизвестный порог сохраняется как отсутствие`() {
        store().write(latest = 95, minSupported = null)

        val v = store().read()
        assertEquals(95, v?.latest)
        assertNull(v?.minSupported, "неизвестный порог не должен превращаться в число")
    }

    @Test
    fun `без файла вердикта нет`() {
        assertNull(store().read(), "до первой проверки хранить нечего")
    }

    @Test
    fun `битый файл не роняет чтение`() {
        File(dir, "update-verdict.txt").writeText("каша\nбез ключей")

        assertNull(store().read(), "мусор — это «не знаем», а не падение")
    }
}
