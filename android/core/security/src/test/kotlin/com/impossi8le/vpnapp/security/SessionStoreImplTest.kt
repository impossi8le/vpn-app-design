package com.impossi8le.vpnapp.security

import com.impossi8le.vpnapp.domain.auth.Session
import com.impossi8le.vpnapp.domain.security.SecureBackend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Фейковое хранилище с точкой отказа: умеет «потерять» ключ Keystore, как это
 * происходит при смене блокировки экрана, сбросе устройства или переустановке.
 * Без этой точки невозможно проверить самое важное свойство — что приложение
 * не падает, а просто считает сессию отсутствующей.
 */
private class FakeSecureBackend : SecureBackend {
    val data = mutableMapOf<String, String>()

    /** Пока `true`, чтение ведёт себя как нерасшифровываемый блоб. */
    var keyLost = false

    override fun put(key: String, value: String) {
        data[key] = value
    }

    override fun get(key: String): String? {
        if (keyLost) throw IllegalStateException("Keystore: ключ недоступен")
        return data[key]
    }

    override fun remove(key: String) {
        data.remove(key)
    }

    override fun clear() = data.clear()
}

class SessionStoreImplTest {

    private val backend = FakeSecureBackend()
    private val store = SessionStoreImpl(backend)

    @Test
    fun `сохранение и чтение сессии сохраняют токен и срок`() {
        val session = Session(token = "tok-abc", expiresAtEpochSeconds = 1_800_000_000L)
        store.save(session)

        val loaded = store.load()
        assertEquals("tok-abc", loaded?.token)
        assertEquals(1_800_000_000L, loaded?.expiresAtEpochSeconds)
    }

    @Test
    fun `пустое хранилище даёт отсутствие сессии, а не падение`() {
        assertNull(store.load())
    }

    @Test
    fun `потеря ключа Keystore не роняет приложение`() {
        store.save(Session("tok-abc", 1_800_000_000L))
        // Так выглядит смена блокировки экрана или сброс устройства.
        backend.keyLost = true

        assertNull(store.load(), "сессии нет — это штатно, а не ошибка")
    }

    @Test
    fun `повреждённый блоб очищается, чтобы не мешать следующей записи`() {
        backend.data["session"] = "не json"
        assertNull(store.load())

        assertEquals(0, backend.data.size, "мусор не должен оставаться в хранилище")
        assertTrue(store.load() == null)
    }

    @Test
    fun `блоб без токена считается отсутствием сессии`() {
        backend.data["session"] = """{"expires_at":123}"""
        assertNull(store.load())
    }

    @Test
    fun `clear удаляет сессию`() {
        store.save(Session("tok", 1L))
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun `повторный clear идемпотентен`() {
        store.save(Session("tok", 1L))
        store.clear()
        store.clear() // не должно бросать
        assertNull(store.load())
    }

    @Test
    fun `новая сессия затирает предыдущую`() {
        store.save(Session("old", 1L))
        store.save(Session("new", 2L))
        assertEquals("new", store.load()?.token)
    }
}
