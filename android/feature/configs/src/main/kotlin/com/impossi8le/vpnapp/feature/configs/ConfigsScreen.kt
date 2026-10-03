package com.impossi8le.vpnapp.feature.configs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.core.ui.VpnRadii
import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus

const val CONFIGS_LIST_TAG = "configs_list"
const val CONFIGS_EMPTY_TAG = "configs_empty"
const val CONFIGS_ERROR_TAG = "configs_error"

/** Таг строки включает id: тесту нужно нажимать конкретное подключение. */
fun configRowTag(id: String) = "config_row_$id"

/**
 * Список подключений (§3 контракта).
 *
 * Три правила, заложенные в разметку:
 *  1. **статус у каждого подключения свой** — истёкшее не скрывает действующие и
 *     не блокирует экран;
 *  2. **пустой список — не ошибка**: это состояние «подписок нет»;
 *  3. **никаких призывов к покупке** — в ответе сервера поля для этого нет
 *     намеренно, и рендерить «продлить» клиент не должен.
 */
@Composable
fun ConfigsScreen(
    state: ConfigsUiState,
    onSelect: (ConfigSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VpnColors.Void)
            .padding(16.dp),
    ) {
        when (state) {
            ConfigsUiState.Loading -> Text(
                text = "Загрузка…",
                color = VpnColors.Ash,
                fontSize = 15.sp,
            )

            ConfigsUiState.Empty -> Text(
                text = "Подключений нет",
                color = VpnColors.TextSecondary,
                fontSize = 15.sp,
                modifier = Modifier.testTag(CONFIGS_EMPTY_TAG),
            )

            is ConfigsUiState.Failed -> Text(
                // Текст зависит от того, осмыслен ли повтор: обещать «повторите»,
                // когда повтор не поможет, значит вводить в заблуждение.
                text = if (state.retryable) "Не удалось загрузить. Попробуйте снова" else "Доступ закрыт",
                color = VpnColors.Red,
                fontSize = 15.sp,
                modifier = Modifier.testTag(CONFIGS_ERROR_TAG),
            )

            is ConfigsUiState.Ready -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(CONFIGS_LIST_TAG),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.configs, key = { it.id }) { config ->
                    ConfigRow(config = config, onSelect = onSelect)
                }
            }
        }
    }
}

@Composable
private fun ConfigRow(config: ConfigSummary, onSelect: (ConfigSummary) -> Unit) {
    val active = config.status == SubscriptionStatus.ACTIVE

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(VpnColors.Carbon, RoundedCornerShape(VpnRadii.Card.dp))
            .let { if (active) it.clickable { onSelect(config) } else it }
            .padding(16.dp)
            .testTag(configRowTag(config.id)),
    ) {
        Text(
            text = config.name,
            // Нерабочее подключение приглушено, но не спрятано: пользователь
            // должен видеть, что оно было.
            color = if (active) VpnColors.Bone else VpnColors.Ash,
            fontSize = 16.sp,
        )
        Text(
            text = statusLabel(config.status),
            // Красный — только реальная опасность. Истёкшая подписка опасностью
            // не является, поэтому янтарный, а не красный.
            color = if (active) VpnColors.Green else VpnColors.Amber,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Подпись статуса.
 *
 * `EXPIRED` и `REVOKED` названы по-разному: «истекла» — это про срок, «отозван» —
 * про решение сервера. Для пользователя это разные ситуации, и свести их к
 * одному слову значит скрыть, что отозванное не вернётся само.
 */
internal fun statusLabel(status: SubscriptionStatus): String = when (status) {
    SubscriptionStatus.ACTIVE -> "Работает"
    SubscriptionStatus.EXPIRED -> "Подписка истекла"
    SubscriptionStatus.REVOKED -> "Доступ отозван"
    SubscriptionStatus.PENDING -> "Ожидает активации"
}
