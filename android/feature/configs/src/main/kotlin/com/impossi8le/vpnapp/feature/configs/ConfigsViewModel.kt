package com.impossi8le.vpnapp.feature.configs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impossi8le.vpnapp.domain.config.ConfigList
import com.impossi8le.vpnapp.domain.config.ConfigService
import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConfigsUiState {
    data object Loading : ConfigsUiState
    data class Ready(val configs: List<ConfigSummary>) : ConfigsUiState

    /**
     * Подписок нет. Это НЕ ошибка и НЕ пустой экран с призывом купить:
     * ответ сервера намеренно не содержит предложения о покупке (§3 контракта).
     */
    data object Empty : ConfigsUiState

    data class Failed(val retryable: Boolean) : ConfigsUiState
}

/**
 * Список подключений (§3 контракта).
 *
 * Три инварианта, которые проверяются тестами:
 *  1. статус у каждого подключения СВОЙ — один истёк, остальные работают;
 *  2. пустой список — валидный ответ, а не ошибка;
 *  3. призывов к покупке нет: поле отсутствует в контракте намеренно.
 */
class ConfigsViewModel(private val service: ConfigService) : ViewModel() {

    private val _state = MutableStateFlow<ConfigsUiState>(ConfigsUiState.Loading)
    val state: StateFlow<ConfigsUiState> = _state.asStateFlow()

    fun refresh() {
        _state.value = ConfigsUiState.Loading
        viewModelScope.launch {
            service.listConfigs().fold(
                onSuccess = { _state.value = toState(it) },
                onFailure = { _state.value = ConfigsUiState.Failed(retryable = true) },
            )
        }
    }

    private fun toState(list: ConfigList): ConfigsUiState =
        if (list.configs.isEmpty()) {
            ConfigsUiState.Empty
        } else {
            // Порядок: сначала действующие, затем остальные. Истёкшие не
            // скрываются — пользователь должен видеть, что было.
            ConfigsUiState.Ready(
                list.configs.sortedBy { it.status != SubscriptionStatus.ACTIVE },
            )
        }
}

/** Действующие подключения: экран не блокируется из-за истёкшего. */
val ConfigsUiState.activeCount: Int
    get() = (this as? ConfigsUiState.Ready)
        ?.configs
        ?.count { it.status == SubscriptionStatus.ACTIVE }
        ?: 0
