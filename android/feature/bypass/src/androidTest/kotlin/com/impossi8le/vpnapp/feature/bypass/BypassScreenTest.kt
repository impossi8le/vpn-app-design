package com.impossi8le.vpnapp.feature.bypass

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.impossi8le.vpnapp.domain.tunnel.BypassCatalog
import com.impossi8le.vpnapp.domain.tunnel.BypassRoute
import com.impossi8le.vpnapp.domain.tunnel.BypassService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Экран «Обходы» как его видит пользователь.
 *
 * ИМЕНА МЕТОДОВ БЕЗ ПРОБЕЛОВ И ОБРАТНЫХ КАВЫЧЕК: эти тесты компилируются в DEX,
 * а D8 до версии 040 запрещает пробелы в именах методов.
 *
 * На JVM уже проверено, что фильтр и разбор ввода выбирают правильно. Здесь —
 * то, чего на JVM не видно: что решение доходит до экрана и что неудача не
 * выглядит как пустой список.
 */
class BypassScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val vk = BypassService("vk", "ВКонтакте", listOf("vk.com", "userapi.com"))

    private fun setScreen(
        state: BypassUiState,
        onBypassAll: () -> Unit = {},
        onRemove: (BypassRoute) -> Unit = {},
        onToggleExpanded: (String) -> Unit = {},
        onAddCustom: () -> Unit = {},
        onCustomInputChange: (String) -> Unit = {},
        onCustomNameChange: (String) -> Unit = {},
    ) {
        compose.setContent {
            BypassScreen(
                state = state,
                onBack = {},
                onReload = {},
                onBypassAll = onBypassAll,
                onQueryChange = {},
                onToggleExpanded = onToggleExpanded,
                onCustomInputChange = onCustomInputChange,
                onCustomNameChange = onCustomNameChange,
                onAddCustom = onAddCustom,
                onRemove = onRemove,
            )
        }
    }

    @Test
    fun bypassAllButtonReportsIntent() {
        var clicked = false
        setScreen(
            state = BypassUiState(catalog = BypassCatalog.Loaded(listOf(vk)), routes = emptyList()),
            onBypassAll = { clicked = true },
        )

        compose.onNodeWithTag(BYPASS_BYPASS_ALL_TAG).performClick()

        assertEquals(true, clicked)
    }

    @Test
    fun tappingServiceRowReportsToggle() {
        var toggled: String? = null
        setScreen(
            state = BypassUiState(catalog = BypassCatalog.Loaded(listOf(vk)), routes = emptyList()),
            onToggleExpanded = { toggled = it },
        )

        compose.onNodeWithTag(bypassServiceTag("vk")).performClick()

        assertEquals("vk", toggled)
    }

    @Test
    fun expandedServiceShowsItsDomains() {
        setScreen(
            state = BypassUiState(
                catalog = BypassCatalog.Loaded(listOf(vk)),
                routes = emptyList(),
                expandedKeys = setOf("vk"),
            ),
        )

        compose.onNodeWithText("vk.com").assertIsDisplayed()
        compose.onNodeWithText("userapi.com").assertIsDisplayed()
    }

    @Test
    fun collapsedServiceHidesItsDomains() {
        setScreen(
            state = BypassUiState(catalog = BypassCatalog.Loaded(listOf(vk)), routes = emptyList()),
        )

        compose.onNodeWithText("vk.com").assertDoesNotExist()
    }

    @Test
    fun searchQueryFiltersTheList() {
        val yandex = BypassService("yandex", "Яндекс", listOf("ya.ru"))
        setScreen(
            state = BypassUiState(
                catalog = BypassCatalog.Loaded(listOf(vk, yandex)),
                routes = emptyList(),
                query = "яндекс",
            ),
        )

        compose.onNodeWithText("Яндекс").assertIsDisplayed()
        compose.onNodeWithText("ВКонтакте").assertDoesNotExist()
    }

    @Test
    fun emptySearchResultSaysSoRatherThanShowingNothing() {
        setScreen(
            state = BypassUiState(
                catalog = BypassCatalog.Loaded(listOf(vk)),
                routes = emptyList(),
                query = "не-существует",
            ),
        )

        compose.onNodeWithTag(BYPASS_SERVICES_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun failedRoutesLoadIsNotShownAsEmptyList() {
        // Неудача чтения и «обходов нет» — разные факты, и экран их не смешивает.
        setScreen(state = BypassUiState(routes = null, routesFailed = true))

        compose.onNodeWithTag(BYPASS_ROUTES_FAILED_TAG).assertIsDisplayed()
        compose.onNodeWithTag(BYPASS_ROUTES_EMPTY_TAG).assertDoesNotExist()
    }

    @Test
    fun emptyRoutesReadAsNoBypasses() {
        setScreen(state = BypassUiState(routes = emptyList()))

        compose.onNodeWithTag(BYPASS_ROUTES_EMPTY_TAG).assertIsDisplayed()
    }

    @Test
    fun failedCatalogIsNotShownAsNoServices() {
        setScreen(state = BypassUiState(catalog = null, servicesFailed = true, routes = emptyList()))

        compose.onNodeWithTag(BYPASS_SERVICES_FAILED_TAG).assertIsDisplayed()
    }

    @Test
    fun removingRouteReportsIt() {
        val route = BypassRoute("87.240.129.0", 24)
        var removed: BypassRoute? = null
        setScreen(
            state = BypassUiState(routes = listOf(route)),
            onRemove = { removed = it },
        )

        compose.onNodeWithTag(bypassRemoveTag("87.240.129.0/24")).performClick()

        assertEquals(route, removed)
    }

    @Test
    fun unsupportedDeviceSaysBypassesAreNotApplied() {
        // На API < 33 `excludeRoute` нет: список можно вести, но он не действует.
        // Показать его «активным» здесь значило бы обещать обход, которого нет.
        setScreen(state = BypassUiState(routes = listOf(BypassRoute("87.240.129.0", 24)), supported = false))

        compose.onNodeWithTag(BYPASS_UNSUPPORTED_TAG).assertIsDisplayed()
    }

    @Test
    fun addCustomButtonReportsIntent() {
        var added = false
        setScreen(
            state = BypassUiState(
                routes = emptyList(),
                customInput = "vk.com",
                expandedKeys = setOf(BYPASS_CUSTOM_KEY),
            ),
            onAddCustom = { added = true },
        )

        compose.onNodeWithTag(BYPASS_CUSTOM_ADD_TAG).performClick()

        assertEquals(true, added)
    }

    @Test
    fun namedRouteShowsNameInsteadOfBareCidr() {
        // «если известен чей ip — надо написать чей»: в списке видно название
        // сервиса, а не безликий 87.240.129.0/24.
        setScreen(
            state = BypassUiState(
                routes = listOf(BypassRoute("87.240.129.0", 24, name = "ВКонтакте")),
            ),
        )

        compose.onNodeWithText("ВКонтакте").assertIsDisplayed()
    }

    @Test
    fun unnamedRouteStillShowsCidr() {
        // Старые записи без подписи не должны пропасть из списка.
        setScreen(state = BypassUiState(routes = listOf(BypassRoute("77.88.0.0", 16))))

        compose.onNodeWithText("77.88.0.0/16").assertIsDisplayed()
    }

    @Test
    fun customNameFieldReportsTypedName() {
        var typed: String? = null
        setScreen(
            state = BypassUiState(
                routes = emptyList(),
                customInput = "1.2.3.0/24",
                expandedKeys = setOf(BYPASS_CUSTOM_KEY),
            ),
            onCustomNameChange = { typed = it },
        )

        compose.onNodeWithTag(BYPASS_CUSTOM_NAME_TAG).performTextInput("Работа")

        assertEquals("Работа", typed)
    }

    @Test
    fun customBlockStartsCollapsed() {
        // Блок «Свой обход» занимает экран всё время, хотя нужен редко: свёрнут,
        // пока его не раскрыли.
        setScreen(state = BypassUiState(routes = emptyList()))

        compose.onNodeWithTag(BYPASS_CUSTOM_INPUT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(BYPASS_CUSTOM_ADD_TAG).assertDoesNotExist()
        compose.onNodeWithTag(BYPASS_CUSTOM_HEADER_TAG).assertIsDisplayed()
    }

    @Test
    fun expandedCustomBlockShowsFields() {
        setScreen(
            state = BypassUiState(routes = emptyList(), expandedKeys = setOf(BYPASS_CUSTOM_KEY)),
        )

        compose.onNodeWithTag(BYPASS_CUSTOM_INPUT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(BYPASS_CUSTOM_ADD_TAG).assertIsDisplayed()
    }

    @Test
    fun tappingCustomHeaderReportsToggle() {
        // Раскрывается тем же механизмом, что и строки сервисов, — тем же ключом.
        var toggled: String? = null
        setScreen(
            state = BypassUiState(routes = emptyList()),
            onToggleExpanded = { toggled = it },
        )

        compose.onNodeWithTag(BYPASS_CUSTOM_HEADER_TAG).performClick()

        assertEquals(BYPASS_CUSTOM_KEY, toggled)
    }

    @Test
    fun collapsingCustomBlockKeepsTypedText() {
        // Свернули блок с набранным текстом — и он вернулся при раскрытии: текст
        // живёт в состоянии экрана (`customInput`/`customName`), а не в разметке,
        // которую снос при сворачивании уничтожил бы.
        var expanded by mutableStateOf(true)
        var input by mutableStateOf("vk.com")
        compose.setContent {
            BypassScreen(
                state = BypassUiState(
                    routes = emptyList(),
                    customInput = input,
                    expandedKeys = if (expanded) setOf(BYPASS_CUSTOM_KEY) else emptySet(),
                ),
                onBack = {},
                onReload = {},
                onBypassAll = {},
                onQueryChange = {},
                onToggleExpanded = { expanded = !expanded },
                onCustomInputChange = { input = it },
                onCustomNameChange = {},
                onAddCustom = {},
                onRemove = {},
            )
        }
        compose.onNodeWithText("vk.com").assertIsDisplayed()

        compose.onNodeWithTag(BYPASS_CUSTOM_HEADER_TAG).performClick()
        compose.onNodeWithTag(BYPASS_CUSTOM_INPUT_TAG).assertDoesNotExist()

        compose.onNodeWithTag(BYPASS_CUSTOM_HEADER_TAG).performClick()
        compose.onNodeWithText("vk.com").assertIsDisplayed()
    }
}
