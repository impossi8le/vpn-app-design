package com.impossi8le.vpnapp.network

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Генерация секрета входа и его хеша.
 *
 * Секрет создаётся на устройстве и НИКОГДА не уходит в открытом виде — на сервер
 * отправляется только `sha256(secret)` (контракт §1). Именно поэтому знание
 * `public_code` из ссылки сессии не даёт.
 */
object Crypto {

    private const val SECRET_BYTES = 32
    private const val PUBLIC_CODE_BYTES = 6

    private val random = SecureRandom()

    fun newSecret(): String = random.bytes(SECRET_BYTES)

    /** Публичный код: короткий, попадает в ссылку и виден в чате. Hex, 12 символов. */
    fun newPublicCode(): String = random.bytes(PUBLIC_CODE_BYTES)

    fun sha256Hex(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))

    /**
     * Хеш байтов.
     *
     * Нужен для сверки тела конфига: профиль приходит байтами, а не строкой
     * (внутри может быть что угодно, включая не-UTF-8), поэтому приведение к
     * строке до хеширования испортило бы результат.
     */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun SecureRandom.bytes(count: Int): String {
        val buffer = ByteArray(count)
        nextBytes(buffer)
        return buffer.joinToString("") { "%02x".format(it) }
    }
}
