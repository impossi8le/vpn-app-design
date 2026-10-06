package com.impossi8le.vpnapp.testsupport

import com.impossi8le.vpnapp.domain.tunnel.BypassCatalog
import com.impossi8le.vpnapp.domain.tunnel.BypassControl
import com.impossi8le.vpnapp.domain.tunnel.BypassResolve
import com.impossi8le.vpnapp.domain.tunnel.BypassRoute
import com.impossi8le.vpnapp.domain.tunnel.BypassWrite

/**
 * Обходы-заглушка.
 *
 * Ответы задаются полями, а не подклассами: тестам нужно перебрать сочетания
 * «каталог не пришёл / resolve отверг / запись прошла» без дублирования класса.
 *
 * [routesResult] по умолчанию — пустой список (успешное чтение «обходов нет»),
 * а не `null`: `null` значит «прочитать не удалось», и делать его исходным
 * значением — значит прятать сбой за удачей в каждом тесте по умолчанию.
 */
class FakeBypassControl(
    var catalogResult: BypassCatalog = BypassCatalog.Loaded(emptyList()),
    var resolveResult: BypassResolve = BypassResolve.Resolved(emptyList()),
    var writeResult: BypassWrite = BypassWrite.Applied(0),
    var routesResult: List<BypassRoute>? = emptyList(),
) : BypassControl {

    /** Что было передано в последний `set`. Проверяет «пишем список целиком». */
    var lastSetRoutes: List<BypassRoute>? = null

    /** Что было передано в последний `resolve`. */
    var lastResolveTargets: List<String>? = null

    /** Список, который `fetchRoutes` отдаёт после записи (чтобы «перечитать» его). */
    var routesAfterWrite: List<BypassRoute>? = null

    var setCount: Int = 0
        private set

    override suspend fun fetchRoutes(): List<BypassRoute>? = routesResult

    override suspend fun catalog(): BypassCatalog = catalogResult

    override suspend fun resolve(targets: List<String>): BypassResolve {
        lastResolveTargets = targets
        return resolveResult
    }

    override suspend fun set(routes: List<BypassRoute>): BypassWrite {
        setCount++
        lastSetRoutes = routes
        // Успешная запись меняет то, что отдаст следующее чтение, — иначе
        // «перечитать после записи» не отличить от «не перечитывать».
        if (writeResult is BypassWrite.Applied && routesAfterWrite != null) {
            routesResult = routesAfterWrite
        }
        return writeResult
    }
}
