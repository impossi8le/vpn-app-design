package com.impossi8le.vpnapp.domain.security

/**
 * Доверенное хранилище секретов. Абстракция нужна, чтобы домен и тесты не знали
 * про Android Keystore: на устройстве это EncryptedSharedPreferences, в тестах —
 * обычный in-memory словарь.
 *
 * Реализация на Android не защищает от root, от процесса с тем же UID и от
 * hooking. Против потери устройства защищает только файловое шифрование при
 * наличии блокировки экрана. Обещать больше нельзя.
 */
interface SecureBackend {
    fun put(key: String, value: String)

    /** `null`, если записи нет ИЛИ блоб не расшифровывается (сменился ключ Keystore). */
    fun get(key: String): String?

    fun remove(key: String)

    fun clear()
}
