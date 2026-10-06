package com.impossi8le.vpnapp.domain.tunnel

/**
 * Сервис из каталога РФ-сервисов: ключ, человеческое имя и его домены.
 *
 * Каталог приходит с сервера целиком: клиент не хранит свой список сервисов, иначе
 * два списка разошлись бы при первой же правке на бэкенде, и «обойти все РФ
 * сервисы» обходило бы уже не то, что сервер считает РФ-сервисами.
 */
data class BypassService(val key: String, val title: String, val domains: List<String>)

/**
 * Каталог РФ-сервисов.
 *
 * Отдельный `Failed`, а не пустой список: [BypassCatalog.Loaded] с пустым списком
 * значит «сервисов нет», а [Failed] — «не смогли спросить». §6 запрещает выдавать
 * второе за первое: показать пустой список там, где ответа не было, — это соврать.
 */
sealed interface BypassCatalog {
    data class Loaded(val services: List<BypassService>) : BypassCatalog
    data object Failed : BypassCatalog
}

/**
 * Разбор адреса в подсети (`POST /app/bypass/resolve`).
 *
 * [InvalidTarget] — это HTTP 400 `invalid_target`: адрес неверный ИЛИ слишком
 * широкий. Отдельно от [Failed], потому что реакция разная: при `400` адрес надо
 * переписать, при [Failed] — повторить запрос.
 *
 * [NotAuthorized] — HTTP 401 `unauthorized`: сессия истекла или отозвана. Отдельно
 * и от [InvalidTarget], и от [Failed]: ни повторить запрос, ни переписать адрес не
 * поможет — нужен новый вход, и экран обязан сказать это словами.
 */
sealed interface BypassResolve {
    data class Resolved(val routes: List<BypassRoute>) : BypassResolve
    data object InvalidTarget : BypassResolve
    data object NotAuthorized : BypassResolve
    data object Failed : BypassResolve
}

/**
 * Запись списка обходов (`POST /app/bypass/set`). Сервер переписывает файл целиком
 * и отвергает ВЕСЬ запрос, если хоть одна запись негодна, — частичной записи нет,
 * поэтому и исход один на весь список.
 */
sealed interface BypassWrite {
    data class Applied(val count: Int) : BypassWrite
    data object Invalid : BypassWrite

    /**
     * HTTP 401 `unauthorized`: сессия недействительна, запись НЕ состоялась.
     *
     * Отдельно от [Failed] и [Invalid]: [Failed] зовёт повторить, [Invalid] —
     * переписать список, а здесь бесполезно и то и другое — нужен новый вход.
     * Слить его с [Failed] значило бы предложить пользователю «повторить» то, что
     * будет отвергнуто столько же раз, сколько он повторит.
     */
    data object NotAuthorized : BypassWrite
    data object Failed : BypassWrite
}

/**
 * Управление обходами с экрана «Обходы».
 *
 * Не слито с [BypassRoutesService]: тот читает список ради подключения и на любой
 * неудаче отдаёт пустой список — различать «нет обходов» и «сервер молчит» ему
 * нечего. Здесь наоборот: экран обязан отличать неудачу от пустоты, поэтому
 * чтение отдаёт `null` при сбое, а записи возвращают типизированный исход.
 */
interface BypassControl {
    /** Список обходов, действующий сейчас. `null` — прочитать не удалось (не пустой список). */
    suspend fun fetchRoutes(): List<BypassRoute>?

    /** Каталог РФ-сервисов. */
    suspend fun catalog(): BypassCatalog

    /** Разобрать домены/адреса в подсети. */
    suspend fun resolve(targets: List<String>): BypassResolve

    /**
     * Записать список обходов (заменяет прежний целиком).
     *
     * Подпись каждой записи — её [BypassRoute.name]: сервер кладёт имя в
     * `#`-комментарий над `route … net_gateway`, и следующее чтение возвращает
     * подпись обратно. Без этого имя жило бы только до перезапуска приложения.
     */
    suspend fun set(routes: List<BypassRoute>): BypassWrite
}

/**
 * Объединение без повторов, порядок — по возрастанию сети и префикса.
 *
 * Порядок фиксирован, а не «как получилось»: список уходит на сервер и в файл,
 * и стабильный порядок делает сравнение и отладку осмысленными.
 *
 * Повтор — совпадение ПО АДРЕСУ, а не по всему объекту: иначе `87.240.129.0/24`
 * с именем `vk.com` и он же без имени считались бы разными и ушли бы на сервер
 * дважды. Имя при этом не теряется: из группы побеждает запись, у которой оно есть.
 */
fun mergeRoutes(existing: List<BypassRoute>, added: List<BypassRoute>): List<BypassRoute> =
    (existing + added)
        .groupBy { it.network to it.prefixLength }
        .map { (_, group) -> group.firstOrNull { it.name != null } ?: group.first() }
        .sortedWith(compareBy({ it.network }, { it.prefixLength }))

/** Убрать одну подсеть. Сервер переписывает файл, отдельного удаления у него нет. */
fun removeRoute(existing: List<BypassRoute>, route: BypassRoute): List<BypassRoute> =
    existing.filterNot { it.sameSubnet(route) }

/** Совпадают ли подсети. Имя в счёте не участвует: адрес и есть тождество записи. */
fun BypassRoute.sameSubnet(other: BypassRoute): Boolean =
    network == other.network && prefixLength == other.prefixLength

/**
 * Все домены каталога — то, что уходит в `resolve` для «обойти все РФ сервисы».
 *
 * Пустые строки отброшены и повторы сняты: сервер отвергает ВЕСЬ запрос из-за
 * одной негодной записи, поэтому чистка здесь, а не надежда на сервер.
 */
fun bypassTargets(services: List<BypassService>): List<String> =
    services.flatMap { it.domains }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
