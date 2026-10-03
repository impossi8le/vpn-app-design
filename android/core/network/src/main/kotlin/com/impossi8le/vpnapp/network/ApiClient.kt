package com.impossi8le.vpnapp.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Общая обвязка HTTP: базовый адрес и клиент с таймаутами.
 *
 * Таймауты заданы явно: опрос входа может висеть долго, а зависший запрос без
 * таймаута оставит пользователя на экране ожидания без объяснения.
 */
class ApiClient(
    val baseUrl: String,
    val http: OkHttpClient = defaultClient(),
) {
    /** Текущий токен сессии; `null` до входа. */
    var sessionToken: String? = null

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}

/** Платформа передаётся на сервер для диагностики (контракт §1). */
const val PLATFORM_ANDROID = "android"
