package com.impossi8le.vpnapp.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.impossi8le.vpnapp.domain.security.SecureBackend

/**
 * Хранилище секретов на EncryptedSharedPreferences: содержимое шифруется ключом
 * из Android Keystore.
 *
 * Честные границы, которые нельзя замалчивать:
 *  - **не защищает** от root, от процесса с тем же UID и от hooking — ключ
 *    достаётся тому, кто уже внутри периметра приложения;
 *  - **не защищает** от разблокированного устройства: `setUserAuthenticationRequired`
 *    не выставлен, поэтому достаточно разблокировки экрана;
 *  - при смене блокировки экрана, сбросе устройства или переустановке ключ
 *    Keystore уничтожается, и старый блоб становится нерасшифровываемым.
 *    Это штатно: `get` вернёт `null`, и пользователь войдёт заново.
 */
class AndroidSecureBackend(context: Context) : SecureBackend {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun get(key: String): String? = try {
        prefs.getString(key, null)
    } catch (_: Exception) {
        // Ключ Keystore сменился или блоб повреждён — сессии больше нет.
        null
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "vpnapp_secure"
    }
}
