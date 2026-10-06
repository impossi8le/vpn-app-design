package com.impossi8le.vpnapp.domain.tunnel

import java.util.Locale

/**
 * Что пользователь ввёл в поле «свой обход».
 *
 * Разбор отдельным типом, а не проверкой в разметке: решение «пусто / заведомо
 * негодно / отправить на сервер» проверяется тестом без рендера.
 */
sealed interface CustomTarget {
    /**
     * Непустой текст. Уходит в `resolve` как есть — окончательное решение за
     * сервером. [name] — необязательная подпись, которую пользователь дал обходу.
     */
    data class Valid(val target: String, val name: String? = null) : CustomTarget

    /** Пусто: дёргать сеть нечем. */
    data object Blank : CustomTarget

    /**
     * Похоже на CIDR, но [parseBypassCidr] его отверг (мусор либо префикс шире /8).
     *
     * Ловим локально, не отправляя: запись `/0` или `/1` значила бы «пусти мимо
     * туннеля весь интернет», и такой запрос незачем даже посылать.
     */
    data object InvalidCidr : CustomTarget
}

/**
 * Разобрать ручной ввод.
 *
 * Домен на этом шаге НЕ проверяем: его годность решает сервер (`resolve`).
 * CIDR — проверяем, и для этого переиспользуем [parseBypassCidr], а не заводим
 * второй парсер: у него уже есть защита от слишком широких префиксов.
 *
 * [name] — то, что введено в поле «Название»: подпись для списка обходов.
 * Отдельным аргументом, а не склейкой с адресом, потому что имя уходит в файл
 * сервера `#`-комментарием, а адрес — в строку `route … net_gateway`. Склейка
 * сломала бы обе строки.
 */
fun classifyCustomTarget(raw: String, name: String? = null): CustomTarget {
    val text = raw.trim()
    if (text.isEmpty()) return CustomTarget.Blank
    if (text.contains('/') && parseBypassCidr(text) == null) return CustomTarget.InvalidCidr
    return CustomTarget.Valid(text, name?.trim()?.takeIf { it.isNotEmpty() })
}

/**
 * Фильтр каталога: по названию и по любому домену, без учёта регистра.
 *
 * `lowercase()` с обеих сторон, а не ASCII-хаки (`upper()`/`lower()` поэлементно):
 * названия сервисов русские, и регистронезависимый поиск по «вк» должен находить
 * «ВКонтакте».
 */
fun filterServices(services: List<BypassService>, query: String): List<BypassService> {
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return services
    return services.filter { service ->
        service.title.lowercase(Locale.ROOT).contains(needle) ||
            service.domains.any { it.lowercase(Locale.ROOT).contains(needle) }
    }
}

/** Человекочитаемая подпись подсети: `87.240.129.0/24`. */
fun BypassRoute.label(): String = "$network/$prefixLength"

/**
 * Что показывать в списке «Обходы сейчас»: имя записи, а без него — голый CIDR.
 *
 * Имя есть у обходов из каталога (название сервиса) и у своих обходов, которым
 * пользователь дал название; у старых записей без подписи остаётся адрес.
 */
fun BypassRoute.displayName(): String = name ?: label()

/**
 * Сводка каталога для шапки: сколько сервисов и сколько доменов всего.
 *
 * Считается по загруженному каталогу, а не по фильтру: пользователь должен
 * видеть размер списка независимо от того, что введено в поиске.
 */
fun catalogSummary(services: List<BypassService>): String {
    val domains = services.sumOf { it.domains.size }
    return "Сервисов: ${services.size}, доменов: $domains"
}
