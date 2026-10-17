package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.tunnel.apiServerIpOf
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient

/**
 * Общая обвязка HTTP: базовый адрес и клиент с таймаутами.
 *
 * Таймауты заданы явно: опрос входа может висеть долго, а зависший запрос без
 * таймаута оставит пользователя на экране ожидания без объяснения.
 *
 * **[baseUrl] разбирается один раз при создании.** Имя нашего сервера
 * (`194-87-252-181.sslip.io`) само содержит адрес, поэтому его резолв не нужен.
 * Это не оптимизация: под VPN DNS-сервер туннеля может не отвечать, и тогда
 * резолв висит до таймаута, а запросы к API падают — проверено на устройстве
 * (issue #34).
 */
class ApiClient(
    val baseUrl: String,
    val http: OkHttpClient = defaultClient(),
) {
    /** Текущий токен сессии; `null` до входа. */
    var sessionToken: String? = null

    companion object {
        /**
         * Клиент для обычных запросов к API.
         *
         * Резолвер подменён на [PinnedDns]: адрес нашего сервера закодирован в
         * его имени, и спрашивать про него DNS не нужно — под VPN это ожидание
         * неотвечающего сервера туннеля (issue #34).
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()

        /**
         * Тот же клиент, но со знанием адреса нашего сервера.
         *
         * Отдельный вход, а не правка [defaultClient]: резолверу нужен базовый
         * адрес, а он известен только владельцу клиента. Здесь собирается
         * клиент по умолчанию и к нему добавляется подмена резолва.
         */
        fun clientFor(baseUrl: String): OkHttpClient =
            defaultClient().newBuilder().dns(PinnedDns(baseUrl)).build()

        /**
         * Клиент для скачивания больших файлов (APK обновления, ~100 МБ).
         *
         * Без `callTimeout`: он ограничивает ВЕСЬ вызов, а не паузу между
         * пакетами, поэтому 45 секунд обрывают закачку на середине — именно так
         * и ломалось обновление. Паузы без данных по-прежнему ограничены
         * `readTimeout`, так что зависшее соединение не держится вечно.
         */
        fun downloadClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Резолвер, который не спрашивает DNS про имя, уже содержащее адрес.
 *
 * **Зачем.** Под поднятым VPN система ставит первым DNS-сервером `10.91.0.1` —
 * адрес туннеля, который не отвечает. Android спрашивает серверы по порядку, и
 * запрос к нашему API ждёт недоступный первый вместо второго: на устройстве это
 * дало **48 секунд** и отказ, тогда как обращение по IP отвечало за 0.1 с
 * (issue #34). Практическое следствие — «Не удалось проверить адрес» при
 * добавлении своего обхода, если VPN включён.
 *
 * **Почему это не ломает приватность.** Подменяется ровно одно имя — адрес
 * нашего сервера, и ровно на тот адрес, который в нём же и закодирован. Для
 * любого другого имени вызывается обычный резолвер: трафик и DNS остальных
 * запросов идут как раньше.
 *
 * **Почему только когда имя само несёт адрес.** `sslip.io` кодирует четыре
 * октета: `194-87-252-181.sslip.io` → `194.87.252.181`. Если хост обычный
 * (например, `vpnbotfree.ru`), разобрать его без сети нельзя, и подменять
 * что-либо значило бы угадывать — такой запрос идёт обычным путём.
 */
class PinnedDns(
    private val baseUrl: String,
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {

    private val pinnedHost: String? = hostOf(baseUrl)
    private val pinnedIp: String? = apiServerIpOf(baseUrl)

    override fun lookup(hostname: String): List<InetAddress> {
        // Сверяем имя без учёта регистра: DNS регистронезависим.
        if (pinnedIp != null && hostname.equals(pinnedHost, ignoreCase = true)) {
            return listOf(InetAddress.getByName(pinnedIp))
        }
        return delegate.lookup(hostname)
    }

    private fun hostOf(url: String): String? {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        val hostPort = afterScheme.substringBefore('/').substringAfter('@')
        return hostPort.substringBefore(':').ifEmpty { null }
    }
}

/** Платформа передаётся на сервер для диагностики (контракт §1). */
const val PLATFORM_ANDROID = "android"
