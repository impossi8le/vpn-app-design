package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ProfileMeta
import com.impossi8le.vpnapp.domain.config.ProfileMetaStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/**
 * Метаданные профиля рядом с самим профилем.
 *
 * Шифровать нечего: `configId`, версия и срок — не секреты, а приватный ключ
 * лежит в `.ovpn`, не здесь. Файл вплотную к профилю, чтобы очистка уносила оба
 * и они не расходились.
 *
 * `load` не бросает: повреждённый файл — это «метаданных нет», и клиент просто
 * сходит за профилем заново. Ронять экран из-за испорченного кэша нельзя.
 */
class FileProfileMetaStore(
    dir: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ProfileMetaStore {

    private val file = File(dir, "profile.meta")

    override fun load(): ProfileMeta? {
        return try {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val configId = root.str("config_id") ?: return null
            val version = root.str("version") ?: return null
            ProfileMeta(
                configId = configId,
                version = version,
                endDateEpochSeconds = root["end_date"]?.jsonPrimitive?.long ?: return null,
                // Поля может не быть: мета от версии до его появления. Это не
                // ошибка — адрес просто неизвестен, и «Сервер: неизвестно»
                // остаётся честным.
                serverHostPort = root.str("server"),
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun save(meta: ProfileMeta) {
        val payload = buildJsonObject {
            put("config_id", JsonPrimitive(meta.configId))
            put("version", JsonPrimitive(meta.version))
            put("end_date", JsonPrimitive(meta.endDateEpochSeconds))
            // Пишем только когда адрес известен: пустое значение выглядело бы
            // как «сервер есть, но пустой».
            meta.serverHostPort?.let { put("server", JsonPrimitive(it)) }
        }
        file.writeText(payload.toString())
    }

    override fun clear() {
        if (file.exists()) file.delete()
    }

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.content
}
