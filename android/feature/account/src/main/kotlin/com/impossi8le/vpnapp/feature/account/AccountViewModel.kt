package com.impossi8le.vpnapp.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impossi8le.vpnapp.domain.auth.SessionStore
import com.impossi8le.vpnapp.domain.config.ProfileStore
import com.impossi8le.vpnapp.domain.tunnel.TunnelControlling
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AccountUiState {
    data class SignedIn(val expiresAtEpochSeconds: Long) : AccountUiState
    data object SignedOut : AccountUiState
}

/**
 * Аккаунт: состояние входа и выход.
 *
 * Выход обязан быть полным, и порядок здесь не случаен:
 *
 *  1. **туннель гасится первым** — иначе ядро продолжит держать открытым
 *     профиль, который мы собираемся удалить, а соединение останется живым
 *     после «выхода»;
 *  2. затем удаляется профиль и его резерв;
 *  3. только потом сессия.
 *
 * Если удалить профиль раньше остановки туннеля, ядро будет читать файл,
 * которого уже нет. Если сессию раньше туннеля — останется поднятое соединение
 * без права им управлять.
 *
 * Отдельно: профиль удаляется вместе с сессией, а не отдельно. Иначе следующая
 * сессия подхватила бы профиль предыдущего пользователя.
 */
class AccountViewModel(
    private val sessionStore: SessionStore,
    private val profileStore: ProfileStore,
    private val tunnel: TunnelControlling,
) : ViewModel() {

    private val _state = MutableStateFlow<AccountUiState>(AccountUiState.SignedOut)
    val state: StateFlow<AccountUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val session = sessionStore.load()
        _state.value = if (session == null) {
            AccountUiState.SignedOut
        } else {
            AccountUiState.SignedIn(session.expiresAtEpochSeconds)
        }
    }

    suspend fun signOut() {
        // 1. Туннель: ядро не должно держать удаляемый профиль.
        tunnel.disconnect()

        // 2. Профиль вместе с резервом и временным файлом.
        profileStore.clear()

        // 3. Сессия.
        sessionStore.clear()

        _state.value = AccountUiState.SignedOut
    }

    fun signOutAsync() {
        viewModelScope.launch { signOut() }
    }
}
