package com.impossi8le.vpnapp.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/**
 * Протокол согласованной записи (§7). Проверяется на НАСТОЯЩЕЙ файловой системе
 * во временном каталоге: смысл протокола — в поведении файлов при обрыве, а
 * словарный фейк такого воспроизвести не может.
 */
class FileProfileStoreTest {

    @TempDir
    lateinit var dir: File

    private val valid = "client\ndev tun\nremote host 1194\n<ca>\nMIIB\n</ca>\n".toByteArray()
    private val other = "client\ndev tun\nremote host 1195\n<ca>\nMIIC\n</ca>\n".toByteArray()
    private val invalid = "это не конфиг".toByteArray()

    private fun store(failing: Boolean = false) =
        FileProfileStore(dir, failingMoves = failing) { raw ->
            String(raw).contains("client") && String(raw).contains("</ca>")
        }

    @Test
    fun `невалидный конфиг не доходит до записи`() {
        assertThrows(IllegalArgumentException::class.java) { store().stage(invalid) }
        assertFalse(File(dir, "profile.ovpn").exists(), "рабочий профиль не должен появиться")
    }

    @Test
    fun `успешный commit оставляет ровно один рабочий профиль`() {
        val s = store()
        s.commit(s.stage(valid))

        assertTrue(File(dir, "profile.ovpn").exists())
        assertFalse(File(dir, "profile.ovpn.tmp").exists(), "временный файл должен быть убран")
        assertNotNull(s.load())
    }

    @Test
    fun `обрыв записи не оставляет систему без рабочего профиля`() {
        val s = store()
        s.commit(s.stage(valid))
        val workingBefore = s.load()!!.raw

        // Вторая запись рвётся на середине.
        val broken = store(failing = true)
        assertThrows(IOException::class.java) { broken.commit(broken.stage(other)) }

        // Главное утверждение: конфиг читается и он прежний, а не полузаписанный.
        val after = store().load()
        assertNotNull(after, "обрыв не должен оставлять систему без профиля")
        assertTrue(workingBefore.contentEquals(after!!.raw), "должна остаться прежняя рабочая версия")
    }

    @Test
    fun `load самовосстанавливается из резерва, если профиль пропал`() {
        val s = store()
        s.commit(s.stage(valid))
        // Профиль исчез (так выглядит обрыв на файловой системе), резерв цел.
        File(dir, "profile.ovpn.bak").writeBytes(valid)
        File(dir, "profile.ovpn").delete()

        assertNotNull(s.load(), "load обязан восстановиться из резерва сам, без явного rollback")
        assertTrue(File(dir, "profile.ovpn").exists())
    }

    @Test
    fun `пустое хранилище читается как отсутствие профиля, а не падение`() {
        assertNull(store().load())
    }

    @Test
    fun `clear удаляет профиль, резерв и временный файл`() {
        val s = store()
        s.commit(s.stage(valid))
        s.clear()

        assertEquals(0, dir.listFiles()!!.size, "после выхода из аккаунта не должно остаться ничего")
    }

    @Test
    fun `повторная запись сохраняет предыдущую версию для отката`() {
        val s = store()
        s.commit(s.stage(valid))
        s.commit(s.stage(other))

        s.rollback()
        val restored = s.load()!!
        assertTrue(String(restored.raw).contains("1194"), "откат должен вернуть прежний remote")
    }
}
