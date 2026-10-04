package com.impossi8le.vpnapp.vpnservice

import android.net.VpnService
import com.impossi8le.vpnapp.vpnengine.TunBridge
import java.net.InetAddress

/**
 * Мост от ядра OpenVPN 3 к `VpnService.Builder`.
 *
 * Ядро не знает про Android: оно просит «добавь адрес», «добавь маршрут»,
 * «поставь DNS», «дай дескриптор» — и ждёт ответа. Этот класс переводит такие
 * просьбы в вызовы `Builder`.
 *
 * **Границы, которые нельзя размывать:**
 *  - `establish()` возвращает файловый дескриптор. Получить его можно только у
 *    живого сервиса, поэтому мост нельзя проверить в тесте — как и `Builder`.
 *  - `allowLocalDns` всегда `false`: локальный DNS означает запросы мимо
 *    туннеля, то есть ту же утечку, что незакрытый IPv6.
 *  - `rerouteGw` — это `redirect-gateway` из профиля. Именно здесь решается,
 *    пойдёт ли весь трафик в туннель. Ядро сообщает о НАМЕРЕНИИ; подтверждает
 *    факт отдельный замер (§6), а не этот вызов.
 */
class VpnServiceTunBuilder(
    private val service: VpnService,
    private val builder: VpnService.Builder,
) : TunBridge {

    private var descriptor: android.os.ParcelFileDescriptor? = null

    override fun new(): Boolean {
        // Новая настройка: сбрасываем прошлое состояние, если оно было.
        closeDescriptor()
        return true
    }

    override fun setMtu(mtu: Int): Boolean = runCatching {
        builder.setMtu(mtu)
    }.isSuccess

    /**
     * Адрес удалённой стороны.
     *
     * `VpnService.Builder` такого метода не имеет: маршрутами и адресами
     * занимается система, а адрес сервера знать ей не нужно. Но вернуть надо
     * именно `true` — базовая реализация отдаёт `false`, а ядро трактует это
     * как «интерфейс не создать» и рвёт установку уже после успешного
     * TLS-рукопожатия с сервером.
     */
    override fun setRemoteAddress(address: String, ipv6: Boolean): Boolean = true

    override fun addAddress(address: String, prefixLength: Int, ipv6: Boolean): Boolean =
        runCatching {
            builder.addAddress(InetAddress.getByName(address), prefixLength)
        }.isSuccess

    override fun addRoute(address: String, prefixLength: Int, ipv6: Boolean): Boolean =
        runCatching {
            builder.addRoute(InetAddress.getByName(address), prefixLength)
        }.isSuccess

    override fun addDns(address: String): Boolean = runCatching {
        builder.addDnsServer(address)
    }.isSuccess

    /**
     * Перехват трафика по умолчанию.
     *
     * Реализуем через маршруты `0.0.0.0/0` и `::/0`, а не «надеждой» на
     * поведение системы: отсутствие настройки IPv6 не означает его блокировку.
     */
    override fun rerouteGw(ipv4: Boolean, ipv6: Boolean, flags: Long): Boolean = runCatching {
        if (ipv4) builder.addRoute(InetAddress.getByName("0.0.0.0"), 0)
        if (ipv6) builder.addRoute(InetAddress.getByName("::"), 0)
    }.isSuccess

    /** Локальный DNS пропускать нельзя: это утечка запросов мимо туннеля. */
    override fun setAllowLocalDns(allow: Boolean): Boolean = !allow

    /**
     * Поднять интерфейс и вернуть дескриптор ядру.
     *
     * `establish()` — единственное место, где нужен живой сервис: система
     * выделяет файловый дескриптор только ему.
     */
    override fun establish(): Int {
        val pfd = builder.establish() ?: run {
            // Система отказала: чаще всего пользователь не дал согласие на VPN.
            return -1
        }
        descriptor = pfd
        (service as? VpnTunnelService)?.tunDescriptor = pfd
        return pfd.fd
    }

    override fun persist(): Boolean = true

    override fun teardown(disconnect: Boolean) {
        closeDescriptor()
    }

    /**
     * Защита сокета: без неё сокет ядра уходит мимо туннеля — то есть утечка,
     * ради исключения которой всё и делается.
     *
     * `VpnService.protect(fd)` возвращает `false`, если защитить не удалось, и
     * это надо вернуть ядру честно: притворяться, что защита стоит, опаснее
     * отказа, потому что трафик пойдёт наружу молча.
     */
    override fun protectSocket(socket: Int): Boolean =
        runCatching { service.protect(socket) }.getOrDefault(false)

    /**
     * Дескриптор надо закрывать: иначе после отключения остаётся висящий fd,
     * а система считает, что туннель ещё жив.
     */
    private fun closeDescriptor() {
        runCatching { descriptor?.close() }
        descriptor = null
        (service as? VpnTunnelService)?.tunDescriptor = null
    }
}
