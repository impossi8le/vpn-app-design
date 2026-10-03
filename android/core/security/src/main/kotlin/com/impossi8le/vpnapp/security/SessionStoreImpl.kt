package com.impossi8le.vpnapp.security

import com.impossi8le.vpnapp.domain.auth.Session
import com.impossi8le.vpnapp.domain.auth.SessionStore
import com.impossi8le.vpnapp.domain.security.SecureBackend
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Хранение сессии поверх [SecureBackend].
 *
 * Ни один метод не бросает: смена блокировки экрана, сброс устройства или
 * переустановка уничтожают ключ Keystore, и блоб становится нерасшифровываемым.
 * Это штатная ситуация — пользователь просто входит заново, — поэтому `load`
 * возвращает `null`, а не падает.
 */
class SessionStoreImpl(
    private val backend: SecureBackend,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SessionStore {

    override fun load(): Session? {
        val raw = backend.get(KEY) ?: return null
        return try {
            val root = json.parseToJsonElement(raw).jsonObject
            val token = root.str("token") ?: return null
            Session(token = token, expiresAtEpochSeconds = root.long("expires_at"))
        } catch (_: Exception) {
            // Повреждённый или нерасшифровываемый блоб — чистим и считаем, что
            // сессии нет. Оставить мусор опаснее: он будет мешать следующей записи.
            backend.remove(KEY)
            null
        }
    }

    override fun save(session: Session) {
        val payload = buildJsonObject {
            put("token", JsonPrimitive(session.token))
            put("expires_at", JsonPrimitive(session.expiresAtEpochSeconds))
        }
        backend.put(KEY, payload.toString())
    }

    override fun clear() {
        backend.remove(KEY)
    }

    private companion object {
        const val KEY = "session"
    }
}

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content
