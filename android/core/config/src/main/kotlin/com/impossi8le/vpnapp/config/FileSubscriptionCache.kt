package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ConfigList
import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.SubscriptionCache
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Кэш списка подключений в приватном каталоге приложения.
 *
 * **Где и в каком виде.** Файл `config-cache.json` в `filesDir` — рядом с
 * профилем и его метаданными, чтобы очистка при выходе уносила всё вместе.
 * Формат JSON, а не построчный, как у `profile.meta`: список — это дерево
 * (подключения с полями), и разбирать его самодельными строками значило бы
 * писать свой парсер там, где `kotlinx.serialization` уже подключён.
 *
 * **Шифровать нечего:** id, страна и срок — не секреты; приватный ключ лежит в
 * `.ovpn`, не здесь.
 *
 * **Запись атомарна** (temp + `ATOMIC_MOVE`, как в `FileProfileStore`): обрыв
 * посреди записи не должен оставить половину JSON, из которой следующий запуск
 * прочитает мусор. Переименование на месте — единственная операция, которая не
 * оставляет промежуточного состояния.
 *
 * `load` не бросает: повреждённый файл — это «кэша нет», клиент сходит за
 * списком заново. Ронять экран из-за испорченного кэша нельзя.
 */
class FileSubscriptionCache(
    dir: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SubscriptionCache {

    private val file = File(dir, FILE_NAME)

    override fun load(): ConfigList? {
        if (!file.exists()) return null
        return try {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val configs = root["configs"]?.jsonArray.orEmpty().map { element ->
                val item = element.jsonObject
                ConfigSummary(
                    id = item.str("id") ?: return null,
                    name = item.str("name") ?: return null,
                    countryCode = item.str("country_code").orEmpty(),
                    city = item.str("city").orEmpty(),
                    startDateEpochSeconds = item["start_date"]?.jsonPrimitive?.long ?: return null,
                    endDateEpochSeconds = item["end_date"]?.jsonPrimitive?.long ?: return null,
                    status = parseStatus(item.str("status")),
                )
            }
            ConfigList(
                chatId = root["chat_id"]?.jsonPrimitive?.long ?: 0L,
                configs = configs,
                connectionsLimit = root["connections_limit"]?.jsonPrimitive?.long?.toInt() ?: 0,
                subscriptionUntilEpochSeconds = root["subscription_until"]?.let { it as? JsonPrimitive }
                    ?.takeUnless { it is JsonNull }?.long,
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun save(list: ConfigList) {
        val payload = buildJsonObject {
            put("chat_id", JsonPrimitive(list.chatId))
            put("connections_limit", JsonPrimitive(list.connectionsLimit))
            put(
                "subscription_until",
                list.subscriptionUntilEpochSeconds?.let { JsonPrimitive(it) } ?: JsonNull,
            )
            put(
                "configs",
                kotlinx.serialization.json.JsonArray(
                    list.configs.map { config ->
                        buildJsonObject {
                            put("id", JsonPrimitive(config.id))
                            put("name", JsonPrimitive(config.name))
                            put("country_code", JsonPrimitive(config.countryCode))
                            put("city", JsonPrimitive(config.city))
                            put("start_date", JsonPrimitive(config.startDateEpochSeconds))
                            put("end_date", JsonPrimitive(config.endDateEpochSeconds))
                            put("status", JsonPrimitive(config.status.toWire()))
                        }
                    },
                ),
            )
        }
        try {
            // temp + атомарное переименование: читатель видит либо прежний файл
            // целиком, либо новый целиком — но никогда обрывок.
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(payload.toString())
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            // Не записалось — не беда: сеть ответит при следующем запуске.
        }
    }

    override fun clear() {
        if (file.exists()) file.delete()
    }

    /** Значение строкового поля или `null`. JSON null — это тоже «нет строки». */
    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    /**
     * Тот же разбор статуса, что и в `ConfigApi`: непонятное значение — «не
     * активен». Кэш обязан читаться теми же правилами, что и сеть, иначе
     * сохранённое и полученное разошлись бы.
     */
    private fun parseStatus(raw: String?): SubscriptionStatus = when (raw) {
        "active" -> SubscriptionStatus.ACTIVE
        "expired" -> SubscriptionStatus.EXPIRED
        "revoked" -> SubscriptionStatus.REVOKED
        "pending" -> SubscriptionStatus.PENDING
        else -> SubscriptionStatus.EXPIRED
    }

    private fun SubscriptionStatus.toWire(): String = when (this) {
        SubscriptionStatus.ACTIVE -> "active"
        SubscriptionStatus.EXPIRED -> "expired"
        SubscriptionStatus.REVOKED -> "revoked"
        SubscriptionStatus.PENDING -> "pending"
    }

    private companion object {
        const val FILE_NAME = "config-cache.json"
    }
}
