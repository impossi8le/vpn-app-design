package com.impossi8le.vpnapp.feature.bypass

import com.impossi8le.vpnapp.domain.tunnel.BypassCatalog
import com.impossi8le.vpnapp.domain.tunnel.BypassResolve
import com.impossi8le.vpnapp.domain.tunnel.BypassRoute
import com.impossi8le.vpnapp.domain.tunnel.BypassService
import com.impossi8le.vpnapp.domain.tunnel.BypassWrite
import com.impossi8le.vpnapp.testsupport.FakeBypassControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Логика экрана «Обходы».
 *
 * Проверяются решения, которые §6 запрещает принимать наугад: писать список
 * ЦЕЛИКОМ, не добавлять свой обход локально при `400`, не выдавать неудачу
 * чтения за «обходов нет», не называть список активным на Android < 13.
 */
class BypassViewModelTest {

    private val scheduler = TestCoroutineScheduler()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun service(key: String, title: String, vararg domains: String) =
        BypassService(key, title, domains.toList())

    // --- Загрузка ---

    @Test
    fun `start грузит каталог и текущий список`() = runTest(scheduler) {
        val fake = FakeBypassControl(
            catalogResult = BypassCatalog.Loaded(listOf(service("vk", "ВКонтакте", "vk.com"))),
            routesResult = listOf(BypassRoute("87.240.129.0", 24)),
        )
        val vm = BypassViewModel(fake, supported = true)

        vm.start()

        assertEquals(listOf(BypassRoute("87.240.129.0", 24)), vm.state.value.routes)
        assertFalse(vm.state.value.routesFailed)
        assertEquals(
            BypassCatalog.Loaded(listOf(service("vk", "ВКонтакте", "vk.com"))),
            vm.state.value.catalog,
        )
        assertFalse(vm.state.value.servicesFailed)
    }

    @Test
    fun `сбой чтения списка помечается, а не выглядит пустым списком`() = runTest(scheduler) {
        val vm = BypassViewModel(FakeBypassControl(routesResult = null), supported = true)

        vm.start()

        assertNull(vm.state.value.routes)
        assertTrue(vm.state.value.routesFailed, "неудача чтения обязана быть видна отдельно от пустоты")
    }

    @Test
    fun `сбой каталога помечается, а не выглядит отсутствием сервисов`() = runTest(scheduler) {
        val vm = BypassViewModel(FakeBypassControl(catalogResult = BypassCatalog.Failed), supported = true)

        vm.start()

        assertNull(vm.state.value.catalog)
        assertTrue(vm.state.value.servicesFailed)
    }

    // --- Разворачивание и поиск ---

    @Test
    fun `разворот сервиса переключается`() = runTest(scheduler) {
        val vm = BypassViewModel(FakeBypassControl(), supported = true)

        vm.toggleExpanded("vk")
        assertTrue("vk" in vm.state.value.expandedKeys)
        vm.toggleExpanded("vk")
        assertFalse("vk" in vm.state.value.expandedKeys)
    }

    @Test
    fun `запрос поиска сохраняется`() = runTest(scheduler) {
        val vm = BypassViewModel(FakeBypassControl(), supported = true)
        vm.onQueryChange("вк")
        assertEquals("вк", vm.state.value.query)
    }

    // --- «Обойти все РФ сервисы» ---

    @Test
    fun `обойти все разбирает все домены и пишет объединение`() = runTest(scheduler) {
        val fake = FakeBypassControl(
            catalogResult = BypassCatalog.Loaded(
                listOf(service("vk", "ВКонтакте", "vk.com", "userapi.com")),
            ),
            resolveResult = BypassResolve.Resolved(listOf(BypassRoute("87.240.129.0", 24))),
            writeResult = BypassWrite.Applied(1),
            routesResult = listOf(BypassRoute("77.88.0.0", 16)),
        )
        fake.routesAfterWrite = listOf(BypassRoute("77.88.0.0", 16), BypassRoute("87.240.129.0", 24))
        val vm = BypassViewModel(fake, supported = true)
        vm.start()

        vm.bypassAll()

        // Разбираем ВСЕ домены каталога одним запросом.
        assertEquals(listOf("vk.com", "userapi.com"), fake.lastResolveTargets)
        // Пишем объединение с тем, что было, а не только новое. Порядок —
        // по возрастанию сети: mergeRoutes сортирует, и это часть контракта.
        assertEquals(
            listOf(BypassRoute("77.88.0.0", 16), BypassRoute("87.240.129.0", 24)),
            fake.lastSetRoutes,
        )
        // И показываем перечитанный с сервера список.
        assertEquals(
            listOf(BypassRoute("77.88.0.0", 16), BypassRoute("87.240.129.0", 24)),
            vm.state.value.routes,
        )
    }

    @Test
    fun `обойти все без каталога честно говорит, что список не загружен`() = runTest(scheduler) {
        val fake = FakeBypassControl(catalogResult = BypassCatalog.Failed)
        val vm = BypassViewModel(fake, supported = true)
        vm.start()

        vm.bypassAll()

        assertEquals(0, fake.setCount, "без каталога писать нечего")
        assertTrue(vm.state.value.message!!.isNotBlank())
        assertTrue(vm.state.value.messageError)
    }

    // --- Свой обход ---

    @Test
    fun `свой домен разбирается и добавляется к списку`() = runTest(scheduler) {
        val fake = FakeBypassControl(
            routesResult = listOf(BypassRoute("77.88.0.0", 16)),
            resolveResult = BypassResolve.Resolved(listOf(BypassRoute("87.240.132.0", 24))),
            writeResult = BypassWrite.Applied(2),
        )
        fake.routesAfterWrite = listOf(BypassRoute("77.88.0.0", 16), BypassRoute("87.240.132.0", 24))
        val vm = BypassViewModel(fake, supported = true)
        vm.start()
        vm.onCustomInputChange("vk.com")

        vm.addCustom()

        assertEquals(listOf("vk.com"), fake.lastResolveTargets)
        assertEquals(
            listOf(BypassRoute("77.88.0.0", 16), BypassRoute("87.240.132.0", 24)),
            fake.lastSetRoutes,
        )
    }

    @Test
    fun `негодный адрес не добавляется локально и объясняется`() = runTest(scheduler) {
        val fake = FakeBypassControl(resolveResult = BypassResolve.InvalidTarget)
        val vm = BypassViewModel(fake, supported = true)
        vm.start()
        vm.onCustomInputChange("0.0.0.0/12")

        vm.addCustom()

        assertEquals(0, fake.setCount, "отвергнутый адрес не должен писаться")
        assertTrue(vm.state.value.messageError)
        assertTrue(vm.state.value.message!!.isNotBlank(), "причина обязана быть словами, а не молчанием")
    }

    @Test
    fun `широкая подсеть отвергается до сети`() = runTest(scheduler) {
        val fake = FakeBypassControl()
        val vm = BypassViewModel(fake, supported = true)
        vm.start()
        vm.onCustomInputChange("0.0.0.0/0")

        vm.addCustom()

        assertNull(fake.lastResolveTargets, "широкий префикс не должен доходить до сервера")
        assertEquals(0, fake.setCount)
        assertTrue(vm.state.value.messageError)
    }

    @Test
    fun `пустое поле не дёргает сеть`() = runTest(scheduler) {
        val fake = FakeBypassControl()
        val vm = BypassViewModel(fake, supported = true)
        vm.start()

        vm.addCustom()

        assertNull(fake.lastResolveTargets)
        assertEquals(0, fake.setCount)
        assertTrue(vm.state.value.messageError)
    }

    // --- Удаление ---

    @Test
    fun `удаление пишет оставшийся список целиком`() = runTest(scheduler) {
        val keep = BypassRoute("77.88.0.0", 16)
        val drop = BypassRoute("87.240.129.0", 24)
        val fake = FakeBypassControl(
            routesResult = listOf(drop, keep),
            writeResult = BypassWrite.Applied(1),
        )
        fake.routesAfterWrite = listOf(keep)
        val vm = BypassViewModel(fake, supported = true)
        vm.start()

        vm.remove(drop)

        assertEquals(listOf(keep), fake.lastSetRoutes)
        assertEquals(listOf(keep), vm.state.value.routes)
    }

    @Test
    fun `удаление без загруженного списка ничего не пишет`() = runTest(scheduler) {
        val fake = FakeBypassControl(routesResult = null)
        val vm = BypassViewModel(fake, supported = true)
        vm.start()

        vm.remove(BypassRoute("87.240.129.0", 24))

        assertEquals(0, fake.setCount)
        assertTrue(vm.state.value.messageError)
    }

    // --- Честность записи ---

    @Test
    fun `отвергнутая сервером запись не выдаётся за успех`() = runTest(scheduler) {
        val fake = FakeBypassControl(writeResult = BypassWrite.Invalid)
        val vm = BypassViewModel(fake, supported = true)
        vm.start()
        vm.onCustomInputChange("vk.com")

        vm.addCustom()

        assertTrue(vm.state.value.messageError)
        // Список остаётся тем, что был изначально (пустым), а не «дополненным».
        assertEquals(emptyList<BypassRoute>(), vm.state.value.routes)
    }

    @Test
    fun `сбой записи не выдаётся за успех`() = runTest(scheduler) {
        val fake = FakeBypassControl(writeResult = BypassWrite.Failed)
        val vm = BypassViewModel(fake, supported = true)
        vm.start()
        vm.onCustomInputChange("vk.com")

        vm.addCustom()

        assertTrue(vm.state.value.messageError)
    }

    // --- Недействительная сессия (401) ---

    @Test
    fun `401 на записи поднимает признак недействительной сессии и не выдаёт успех`() = runTest(scheduler) {
        val fake = FakeBypassControl(
            resolveResult = BypassResolve.Resolved(listOf(BypassRoute("87.240.132.0", 24))),
            writeResult = BypassWrite.NotAuthorized,
        )
        val vm = BypassViewModel(fake, supported = true)
        vm.start()
        vm.onCustomInputChange("vk.com")

        vm.addCustom()

        assertTrue(vm.state.value.unauthenticated, "401 обязан быть виден экрану отдельно")
        assertTrue(vm.state.value.messageError)
        // Список меняется только после успешной записи — иначе он врал бы «сохранено».
        assertEquals(emptyList<BypassRoute>(), vm.state.value.routes)
    }

    @Test
    fun `401 на resolve тоже зовёт войти, а не переписать адрес`() = runTest(scheduler) {
        val fake = FakeBypassControl(resolveResult = BypassResolve.NotAuthorized)
        val vm = BypassViewModel(fake, supported = true)
        vm.start()
        vm.onCustomInputChange("vk.com")

        vm.addCustom()

        assertTrue(vm.state.value.unauthenticated)
        assertEquals(0, fake.setCount, "при недействительной сессии писать нечего")
    }

    @Test
    fun `обычная неудача не поднимает признак недействительной сессии`() = runTest(scheduler) {
        val fake = FakeBypassControl(writeResult = BypassWrite.Failed)
        val vm = BypassViewModel(fake, supported = true)
        vm.start()
        vm.onCustomInputChange("vk.com")

        vm.addCustom()

        assertFalse(vm.state.value.unauthenticated, "сбой сети — это не истёкшая сессия")
        assertTrue(vm.state.value.messageError)
    }

    @Test
    fun `новая операция сбрасывает признак недействительной сессии`() = runTest(scheduler) {
        val fake = FakeBypassControl(
            resolveResult = BypassResolve.Resolved(listOf(BypassRoute("87.240.132.0", 24))),
            writeResult = BypassWrite.NotAuthorized,
        )
        val vm = BypassViewModel(fake, supported = true)
        vm.start()
        vm.onCustomInputChange("vk.com")
        vm.addCustom()
        assertTrue(vm.state.value.unauthenticated)

        // Следующая попытка (например, после входа) не должна тащить старую плашку.
        fake.writeResult = BypassWrite.Applied(1)
        vm.onCustomInputChange("vk.com")
        vm.addCustom()

        assertFalse(vm.state.value.unauthenticated)
    }

    @Test
    fun `после записи флаг занятости снят`() = runTest(scheduler) {
        val fake = FakeBypassControl(
            resolveResult = BypassResolve.Resolved(listOf(BypassRoute("87.240.132.0", 24))),
            writeResult = BypassWrite.Applied(1),
        )
        val vm = BypassViewModel(fake, supported = true)
        vm.start()
        vm.onCustomInputChange("vk.com")

        vm.addCustom()

        assertFalse(vm.state.value.applying, "флаг занятости обязан сниматься и при удаче")
    }

    // --- Поддержка устройством ---

    @Test
    fun `флаг поддержки доходит до состояния`() = runTest(scheduler) {
        val vm = BypassViewModel(FakeBypassControl(), supported = false)
        assertFalse(vm.state.value.supported, "на API < 33 экран не должен называть список активным")
    }
}
