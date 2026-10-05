package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ProfileMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileProfileMetaStoreTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `сохранённые метаданные читаются обратно`() {
        val store = FileProfileMetaStore(dir)
        val meta = ProfileMeta("GEclient94", "2026-10-02T12:45:56Z", 1_000_000L)

        store.save(meta)

        assertEquals(meta, store.load())
    }

    @Test
    fun `пустое хранилище возвращает null`() {
        assertNull(FileProfileMetaStore(dir).load())
    }

    @Test
    fun `повреждённый файл не роняет приложение`() {
        File(dir, "profile.meta").writeText("{ это не json")

        assertNull(FileProfileMetaStore(dir).load())
    }

    @Test
    fun `clear удаляет метаданные`() {
        val store = FileProfileMetaStore(dir)
        store.save(ProfileMeta("GEclient94", "v1", 1_000_000L))

        store.clear()

        assertNull(store.load())
    }
}
