package com.impossi8le.vpnapp.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.impossi8le.vpnapp.domain.auth.AuthService
import com.impossi8le.vpnapp.domain.auth.LoginChallenge
import com.impossi8le.vpnapp.domain.auth.PollOutcome
import com.impossi8le.vpnapp.domain.auth.SessionStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Состояния входа. Отдельное «нужен код из бота» — не деталь: это единственный
 * шаг, который пользователь делает руками, и без него вход не завершится.
 */
sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Starting : AuthUiState

    /** Открыт бот, ждём, пока пользователь введёт код. */
    data class AwaitingNonce(val challenge: LoginChallenge) : AuthUiState

    data class Polling(val challenge: LoginChallenge, val nonce: String) : AuthUiState
    data class SignedIn(val token: String) : AuthUiState

    /** Вход истёк или отклонён — нужен новый. */
    data class Failed(val reason: String, val retryable: Boolean) : AuthUiState
}

/**
 * Логика входа (контракт §1–2).
 *
 * Здесь нет секрета в открытом виде ни в состояниях, ни в логах: `secret` живёт
 * внутри [LoginChallenge] и уходит только в `pollSession`.
 *
 * Отдельно обрабатывается потерянный ответ: если повторный опрос вернул
 * `AlreadyConsumed`, пользователь НЕ блокируется навсегда — предлагается войти
 * заново. Повторная выдача той же сессии опаснее повторного входа.
 */
class AuthViewModel(
    private val service: AuthService,
    private val sessionStore: SessionStore,
    deviceName: String,
) : ViewModel() {

    /**
     * Имя устройства для поддержки.
     *
     * Подставляется в последний момент, а не принимается как есть: раньше поле
     * приходило пустым, потому что вызывающий его не заполнял, и на сервер
     * уходила пустая строка. Поддержка оставалась без модели телефона — а
     * именно ради неё поле и заведено в контракте.
     *
     * Если вызывающий не передал ничего, отправляем `Android`: это честнее
     * пустоты — понятно, что клиент не смог определить модель, а не что сервер
     * потерял поле.
     */
    private val deviceName: String = deviceName.trim().ifEmpty { "Android" }

    private val _state = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    private var pollJob: Job? = null

    fun startLogin() {
        if (pollJob?.isActive == true) return
        _state.value = AuthUiState.Starting

        viewModelScope.launch {
            service.startLogin(deviceName).fold(
                onSuccess = { _state.value = AuthUiState.AwaitingNonce(it) },
                onFailure = { _state.value = AuthUiState.Failed("не удалось начать вход", retryable = true) },
            )
        }
    }

    /**
     * Пользователь ввёл код, который увидел в боте.
     *
     * Опрос идёт циклом до терминального состояния: `Pending` — штатный ответ,
     * а не ошибка, и повторять надо с паузой, которую просит сервер.
     */
    fun submitNonce(nonce: String) {
        val challenge = (_state.value as? AuthUiState.AwaitingNonce)?.challenge ?: return
        if (nonce.isBlank()) {
            _state.value = AuthUiState.Failed("введите код из Telegram", retryable = true)
            return
        }

        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            _state.value = AuthUiState.Polling(challenge, nonce)

            while (true) {
                val outcome = service.pollSession(challenge.publicCode, challenge.secret, nonce)
                    .getOrElse {
                        _state.value = AuthUiState.Failed("нет связи с сервером", retryable = true)
                        return@launch
                    }

                when (outcome) {
                    is PollOutcome.Confirmed -> {
                        sessionStore.save(outcome.session)
                        _state.value = AuthUiState.SignedIn(outcome.session.token)
                        return@launch
                    }
                    is PollOutcome.Pending -> {
                        _state.value = AuthUiState.Polling(challenge, nonce)
                        delay(outcome.retryAfterMillis.coerceAtLeast(500))
                    }
                    // Код не совпал — операция жива, код можно ввести заново.
                    // Это НЕ повод начинать вход сначала.
                    PollOutcome.Denied ->
                        _state.value = AuthUiState.Failed("вход отклонён в боте", retryable = false)
                    PollOutcome.Expired ->
                        _state.value = AuthUiState.Failed("время входа истекло", retryable = true)
                    PollOutcome.AttemptLimitExceeded ->
                        _state.value = AuthUiState.Failed("слишком много попыток", retryable = true)
                    // Потерянный ответ: сессия уже выдана, но до нас не дошла.
                    // Начинаем заново, а не зависаем.
                    PollOutcome.AlreadyConsumed ->
                        _state.value = AuthUiState.Failed("сессия уже была выдана — войдите заново", retryable = true)
                }
                if (outcome !is PollOutcome.Pending) return@launch
            }
        }
    }

    /** Неверный код: возвращаемся к вводу, операция ещё жива. */
    fun nonceRejected() {
        val polling = _state.value as? AuthUiState.Polling ?: return
        pollJob?.cancel()
        _state.value = AuthUiState.AwaitingNonce(polling.challenge)
    }

    fun signOut() {
        pollJob?.cancel()
        sessionStore.clear()
        _state.value = AuthUiState.Idle
    }

    override fun onCleared() {
        pollJob?.cancel()
        super.onCleared()
    }
}
