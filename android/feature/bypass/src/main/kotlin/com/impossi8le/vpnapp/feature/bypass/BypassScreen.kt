package com.impossi8le.vpnapp.feature.bypass

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.MinTouchTarget
import com.impossi8le.vpnapp.core.ui.MonoFont
import com.impossi8le.vpnapp.core.ui.SectionLabel
import com.impossi8le.vpnapp.core.ui.Tone
import com.impossi8le.vpnapp.core.ui.VpnCard
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnGhostButton
import com.impossi8le.vpnapp.core.ui.VpnNavBar
import com.impossi8le.vpnapp.core.ui.VpnNotice
import com.impossi8le.vpnapp.core.ui.VpnPrimaryButton
import com.impossi8le.vpnapp.domain.tunnel.BypassCatalog
import com.impossi8le.vpnapp.domain.tunnel.BypassRoute
import com.impossi8le.vpnapp.domain.tunnel.BypassService
import com.impossi8le.vpnapp.domain.tunnel.catalogSummary
import com.impossi8le.vpnapp.domain.tunnel.filterServices
import com.impossi8le.vpnapp.domain.tunnel.label

const val BYPASS_BACK_TAG = "bypass_back"
const val BYPASS_TITLE_TAG = "bypass_title"
const val BYPASS_BYPASS_ALL_TAG = "bypass_all"
const val BYPASS_SEARCH_TAG = "bypass_search"
const val BYPASS_CUSTOM_INPUT_TAG = "bypass_custom_input"
const val BYPASS_CUSTOM_ADD_TAG = "bypass_custom_add"
const val BYPASS_ROUTES_FAILED_TAG = "bypass_routes_failed"
const val BYPASS_ROUTES_EMPTY_TAG = "bypass_routes_empty"
const val BYPASS_SERVICES_FAILED_TAG = "bypass_services_failed"
const val BYPASS_SERVICES_EMPTY_TAG = "bypass_services_empty"
const val BYPASS_MESSAGE_TAG = "bypass_message"
const val BYPASS_UNSUPPORTED_TAG = "bypass_unsupported"
const val BYPASS_APPLYING_TAG = "bypass_applying"

/** Тег строки сервиса: тесту нужно нажать конкретный. */
fun bypassServiceTag(key: String) = "bypass_service_$key"

/** Тег ссылки удаления подсети. */
fun bypassRemoveTag(label: String) = "bypass_remove_$label"

/**
 * Экран «Обходы».
 *
 * Раскладка ничего не решает: что показать — приходит в [state], а фильтрация и
 * разбор ввода живут в `core:domain` (`filterServices`, `classifyCustomTarget`) и
 * проверяются на JVM. Здесь только показать.
 *
 * **Честность (§6).** Список обходов показывается ТОЛЬКО из ответа сервера: при
 * неудаче чтения экран говорит словами, что список не пришёл, а не рисует пустой
 * список. На Android < 13 список показывается как «записанные, но не действующие»
 * — `excludeRoute` там нет, и называть его активным значило бы обещать обход,
 * которого нет.
 */
@Composable
fun BypassScreen(
    state: BypassUiState,
    onBack: () -> Unit,
    onBypassAll: () -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleExpanded: (String) -> Unit,
    onCustomInputChange: (String) -> Unit,
    onAddCustom: () -> Unit,
    onRemove: (BypassRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filtered = filterServices(
        (state.catalog as? BypassCatalog.Loaded)?.services.orEmpty(),
        state.query,
    )

    Column(modifier = modifier.fillMaxSize().background(VpnColors.Void)) {
        VpnNavBar(
            title = "Обходы",
            onBack = onBack,
            backTestTag = BYPASS_BACK_TAG,
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // Обход на этом устройстве не действует (Android < 13, нет
            // `excludeRoute`). Это не ошибка — но список ниже записан, а не
            // применён, и пользователь обязан это знать.
            if (!state.supported) {
                item {
                    VpnNotice(
                        text = "На этой версии Android обходы не применяются: система не " +
                            "умеет исключать маршруты. Список можно вести, но трафик пойдёт " +
                            "через туннель.",
                        tone = Tone.Warning,
                        icon = "!",
                        testTag = BYPASS_UNSUPPORTED_TAG,
                    )
                }
            }

            if (state.applying) {
                item {
                    Text(
                        "Применяем изменения…",
                        color = VpnColors.Amber,
                        fontSize = 12.sp,
                        fontFamily = MonoFont,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(BYPASS_APPLYING_TAG),
                    )
                }
            }

            state.message?.let { message ->
                item {
                    VpnNotice(
                        text = message,
                        // Red — только реальная опасность; ошибка записи обхода —
                        // это несостоявшееся действие, поэтому Warning.
                        tone = if (state.messageError) Tone.Warning else Tone.Neutral,
                        testTag = BYPASS_MESSAGE_TAG,
                    )
                }
            }

            item { SectionLabel(text = "Обходы сейчас") }

            routesSection(state, onRemove)

            item {
                VpnPrimaryButton(
                    text = "Обойти все РФ сервисы",
                    onClick = onBypassAll,
                    // Пока идёт запись — кнопка занята: второй тап не должен
                    // запускать вторую запись поверх первой.
                    enabled = !state.applying,
                    testTag = BYPASS_BYPASS_ALL_TAG,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            item { SectionLabel(text = "РФ-сервисы") }

            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    label = { Text("Поиск по названию или домену") },
                    colors = darkFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(BYPASS_SEARCH_TAG),
                )
            }

            if (state.servicesFailed) {
                item {
                    VpnNotice(
                        text = "Не удалось получить список сервисов. Проверьте связь и " +
                            "откройте экран заново.",
                        tone = Tone.Warning,
                        icon = "!",
                        testTag = BYPASS_SERVICES_FAILED_TAG,
                    )
                }
            } else {
                val loaded = state.catalog as? BypassCatalog.Loaded
                if (loaded != null) {
                    item {
                        Text(
                            catalogSummary(loaded.services),
                            color = VpnColors.Ash,
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                    }
                }

                if (loaded != null && filtered.isEmpty()) {
                    item {
                        Text(
                            "Ничего не найдено",
                            color = VpnColors.TextSecondary,
                            fontSize = 13.5.sp,
                            modifier = Modifier.testTag(BYPASS_SERVICES_EMPTY_TAG),
                        )
                    }
                }

                items(filtered, key = { it.key }) { service ->
                    ServiceRow(
                        service = service,
                        expanded = service.key in state.expandedKeys,
                        onToggle = { onToggleExpanded(service.key) },
                    )
                }
            }

            item { SectionLabel(text = "Свой обход") }

            item {
                OutlinedTextField(
                    value = state.customInput,
                    onValueChange = onCustomInputChange,
                    singleLine = true,
                    label = { Text("Домен или подсеть (CIDR)") },
                    colors = darkFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(BYPASS_CUSTOM_INPUT_TAG),
                )
            }

            item {
                VpnGhostButton(
                    text = "Добавить обход",
                    onClick = { if (!state.applying) onAddCustom() },
                    testTag = BYPASS_CUSTOM_ADD_TAG,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            item { Box(modifier = Modifier.padding(bottom = 12.dp)) }
        }
    }
}

/**
 * Список действующих обходов. Три состояния различимы: «ещё грузим», «не
 * удалось прочитать» и «обходов нет» — это три разных факта, а не один пустой
 * список (§6).
 *
 * Не `@Composable`: это расширение [LazyListScope], а `item { }` внутри само
 * даёт composable-лямбду.
 */
private fun LazyListScope.routesSection(
    state: BypassUiState,
    onRemove: (BypassRoute) -> Unit,
) {
    val routes = state.routes
    when {
        routes == null && state.routesFailed -> item {
            VpnNotice(
                text = "Не удалось получить текущие обходы. Список ниже может быть неполным.",
                tone = Tone.Warning,
                icon = "!",
                testTag = BYPASS_ROUTES_FAILED_TAG,
            )
        }

        routes == null -> item {
            Text(
                "Загружаем список…",
                color = VpnColors.Ash,
                fontSize = 13.5.sp,
            )
        }

        routes.isEmpty() -> item {
            Text(
                "Обходов нет",
                color = VpnColors.TextSecondary,
                fontSize = 13.5.sp,
                modifier = Modifier.testTag(BYPASS_ROUTES_EMPTY_TAG),
            )
        }

        else -> items(routes, key = { it.label() }) { route ->
            RouteRow(route = route, onRemove = { onRemove(route) })
        }
    }
}

/** Строка подсети с ссылкой удаления. */
@Composable
private fun RouteRow(route: BypassRoute, onRemove: () -> Unit) {
    VpnCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                route.label(),
                color = VpnColors.Bone,
                fontSize = 13.5.sp,
                fontFamily = MonoFont,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .heightIn(min = MinTouchTarget)
                    .testTag(bypassRemoveTag(route.label()))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Text("Удалить", color = VpnColors.Ice, fontSize = 13.5.sp)
            }
        }
    }
}

/**
 * Строка РФ-сервиса. Тап разворачивает домены; сам тап — по всей строке, потому
 * что домены и есть то, что человек проверяет, а «проверить» — это посмотреть.
 */
@Composable
private fun ServiceRow(
    service: BypassService,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    VpnCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .testTag(bypassServiceTag(service.key)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        service.title,
                        color = VpnColors.Bone,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "Доменов: ${service.domains.size}",
                        color = VpnColors.Ash,
                        fontSize = 11.5.sp,
                    )
                }
                // Указатель развёрнутости: не цвет, а знак — состояние читается и
                // без него, но с ним заметнее.
                Text(
                    if (expanded) "▾" else "▸",
                    color = VpnColors.Ice,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .size(24.dp)
                        .padding(start = 4.dp),
                )
            }

            if (expanded) {
                HorizontalDivider(
                    color = VpnColors.Hairline,
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                service.domains.forEach { domain ->
                    Text(
                        domain,
                        color = VpnColors.Mist,
                        fontSize = 12.5.sp,
                        fontFamily = MonoFont,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Цвета поля ввода на тёмном фоне.
 *
 * Заданы явно: на `VpnColors.Void` умолчание Material3 рисует текст почти чёрным,
 * и введённое не видно. Те же токены, что на экране входа, — новых цветов нет.
 */
@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = VpnColors.Bone,
    unfocusedTextColor = VpnColors.Bone,
    disabledTextColor = VpnColors.Bone,
    cursorColor = VpnColors.Ice,
    focusedBorderColor = VpnColors.Ice,
    unfocusedBorderColor = VpnColors.Edge,
    focusedLabelColor = VpnColors.TextSecondary,
    unfocusedLabelColor = VpnColors.TextSecondary,
)
