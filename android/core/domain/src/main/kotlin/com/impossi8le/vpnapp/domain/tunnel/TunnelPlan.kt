package com.impossi8le.vpnapp.domain.tunnel

/**
 * Адресация туннеля и DNS, полученные из профиля.
 *
 * Отдельный тип, потому что это чистые данные: их можно проверить тестом, не
 * поднимая `VpnService`.
 */
data class TunnelAddressing(
    val ipv4Address: String,
    val ipv4PrefixLength: Int,
    val dnsServers: List<String>,
    val mtu: Int = 1400,
)

/**
 * План туннеля — всё, что приложение собирается передать в `VpnService.Builder`,
 * как значение.
 *
 * Причина существования: настройка `Builder` — это код, который нельзя
 * проверить без устройства. Но сами ПРАВИЛА настройки — чистые данные, и их
 * обязательно надо проверять: ошибка здесь означает не «неудобно», а «трафик
 * уходит мимо туннеля». Поэтому правила вынесены сюда, где они тестируются на
 * JVM, а в `:vpnservice` остаётся тонкая проекция значения на `Builder`.
 *
 * Конструктор закрыт `require`-проверками: невозможно собрать план, который
 * нарушает инварианты защиты. Проверка стоит на входе, а не на выходе.
 */
class TunnelPlan private constructor(
    val addresses: List<String>,
    val routes: List<String>,
    val dnsServers: List<String>,
    val blocking: Boolean,
    val mtu: Int,
    val disallowedApplications: List<String>,
) {
    companion object {
        /** Маршрут «весь IPv4 внутрь туннеля». */
        const val ROUTE_IPV4_DEFAULT = "0.0.0.0/0"

        /**
         * Маршрут, закрывающий IPv6.
         *
         * Именно ЯВНЫЙ маршрут, а не отсутствие настройки: если IPv6 не
         * указан вовсе, система может продолжать выпускать IPv6-трафик в обход
         * туннеля. Отсутствие настройки — не блокировка.
         */
        const val ROUTE_IPV6_DEFAULT = "::/0"

        /**
         * Построить план туннеля.
         *
         * @throws IllegalArgumentException если план нарушает инварианты защиты.
         */
        fun build(addressing: TunnelAddressing, blocking: Boolean = true): TunnelPlan {
            require(addressing.dnsServers.isNotEmpty()) {
                "DNS обязан прийти из профиля: без него запросы уйдут системному резолверу мимо туннеля"
            }
            require(blocking) {
                "blocking=false оставляет окно, в котором трафик уходит открытым при обрыве"
            }
            require(addressing.mtu in 576..1500) {
                "MTU ${addressing.mtu} вне разумного диапазона"
            }

            return TunnelPlan(
                addresses = listOf("${addressing.ipv4Address}/${addressing.ipv4PrefixLength}"),
                // Оба маршрута задаются явно. Маршрут по умолчанию и закрытие
                // IPv6 — не одно и то же: без второго весь IPv6 идёт мимо.
                routes = listOf(ROUTE_IPV4_DEFAULT, ROUTE_IPV6_DEFAULT),
                // DNS берётся ТОЛЬКО из профиля. Системный резолвер означал бы
                // утечку запросов в обход туннеля.
                dnsServers = addressing.dnsServers,
                blocking = blocking,
                mtu = addressing.mtu,
                // Пусто осознанно: любое исключённое приложение (в том числе
                // само приложение через addDisallowedApplication) обходит
                // туннель, а в таблице маршрутов это не отражается — проба
                // такого не увидит.
                disallowedApplications = emptyList(),
            )
        }
    }

    /** Закрыт ли IPv6 для трафика в обход туннеля. */
    val ipv6Closed: Boolean get() = routes.contains(ROUTE_IPV6_DEFAULT)

    /** Весь ли IPv4-трафик направлен в туннель. */
    val ipv4Captured: Boolean get() = routes.contains(ROUTE_IPV4_DEFAULT)
}
