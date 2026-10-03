package com.impossi8le.vpnapp.core.protection

import android.net.ConnectivityManager
import android.net.LinkProperties
import com.impossi8le.vpnapp.domain.protection.LinkFacts
import com.impossi8le.vpnapp.domain.protection.ProtectionFailure
import com.impossi8le.vpnapp.domain.protection.ProtectionProbe
import com.impossi8le.vpnapp.domain.protection.ProtectionVerdict
import com.impossi8le.vpnapp.domain.protection.evaluateFacts

/**
 * Проба защиты на Android.
 *
 * Это ТОНКИЙ адаптер, и таким он должен остаться. Здесь только чтение линка и
 * упаковка в [LinkFacts]; решение принимает чистая `evaluateFacts` в
 * `core:domain`. Причина: этот файл без устройства не проверить вообще, а логика
 * §6 должна быть проверяемой — значит максимум логики обязан жить по ту сторону
 * границы, в JVM-тестах.
 *
 * ГРАНИЦА ЧЕСТНОСТИ. Когда туннель поднят, `activeNetwork` И ЕСТЬ VPN-сеть,
 * поэтому `getLinkProperties` возвращает то, что приложение само записало в
 * `Builder`. Проба ловит забытый `::/0`, не переданный DNS и снятый перехват
 * IPv4, но НЕ ловит: самоисключение приложения из перехвата, подмену DNS через
 * Private DNS/DoH, и упавшее рукопожатие с уже установленными маршрутами.
 * Поэтому проба — необходимая, но не достаточная проверка (§6, §14).
 *
 * [dnsFromProfile] — резолверы из профиля, эталон для сверки.
 */
class AndroidProtectionProbe(
    private val connectivity: ConnectivityManager,
    private val dnsFromProfile: List<String>,
) : ProtectionProbe {

    override suspend fun verify(): ProtectionVerdict {
        val props: LinkProperties = try {
            connectivity.activeNetwork?.let { connectivity.getLinkProperties(it) }
                ?: return ProtectionVerdict.Failed(ProtectionFailure.ProbeUnavailable)
        } catch (_: Exception) {
            // Нет активной сети или системный вызов бросил — проба не смогла
            // ответить. Это провал защиты, а не «проверяем вечно».
            return ProtectionVerdict.Failed(ProtectionFailure.ProbeUnavailable)
        }

        return evaluateFacts(readFacts(props), dnsFromProfile)
    }

    private fun readFacts(props: LinkProperties): LinkFacts = LinkFacts(
        routes = props.routes.mapNotNull { route ->
            val address = route.destination.address?.hostAddress ?: return@mapNotNull null
            "${address}/${route.destination.prefixLength}"
        },
        dnsServers = props.dnsServers.mapNotNull { it.hostAddress },
    )
}
