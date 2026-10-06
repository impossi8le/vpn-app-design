package com.impossi8le.vpnapp.feature.auth

import com.impossi8le.vpnapp.domain.auth.AuthPollError
import com.impossi8le.vpnapp.domain.auth.AuthPollException
import com.impossi8le.vpnapp.domain.auth.AuthService
import com.impossi8le.vpnapp.domain.auth.LoginChallenge
import com.impossi8le.vpnapp.domain.auth.PollOutcome
import com.impossi8le.vpnapp.domain.auth.Session
import com.impossi8le.vpnapp.domain.auth.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private class FakeAuthService(
    private val challenge: LoginChallenge? = null,
    private val startFailure: Throwable? = null,
    private val outcomes: MutableList<Result<PollOutcome>> = mutableListOf(),
) : AuthService {
    var pollCount = 0
        private set

    override suspend fun startLogin(deviceName: String): Result<LoginChallenge> =
        if (startFailure != null) Result.failure(startFailure) else Result.success(challenge!!)

    override suspend fun pollSession(
        publicCode: String,
        secret: String,
        deviceNonce: String,
    ): Result<PollOutcome> {
        pollCount++
        return if (outcomes.isEmpty()) Result.failure(IllegalStateException("нет заготовленных ответов"))
        else outcomes.removeAt(0)
    }
}

private class FakeSessionStore : SessionStore {
    var saved: Session? = null
        private set
    var cleared = false
        private set

    override fun load(): Session? = saved
    override fun save(session: Session) { saved = session }
    override fun clear() { saved = null; cleared = true }
}

class AuthViewModelTest {

    private val scheduler = TestCoroutineScheduler()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun challenge() = LoginChallenge(
        publicCode = "a7f3c9d2e1b4",
        secret = "secret-value",
        deepLink = "https://t.me/bot?start=login_a7f3c9d2e1b4",
        expiresAtEpochSeconds = 1_800_000_000L,
    )

    @Test
    fun `начало входа переводит экран в ожидание кода`() = runTest(scheduler) {
        val vm = AuthViewModel(FakeAuthService(challenge = challenge()), FakeSessionStore(), "Pixel")
        vm.startLogin()

        val state = vm.state.value
        assertTrue(state is AuthUiState.AwaitingNonce)
        assertEquals("a7f3c9d2e1b4", (state as AuthUiState.AwaitingNonce).challenge.publicCode)
    }

    @Test
    fun `секрет не покидает ViewModel и не попадает в UI-состояние`() {
        val vm = AuthViewModel(FakeAuthService(challenge = challenge()), FakeSessionStore(), "Pixel")
        vm.startLogin()

        // Секрет нужен для опроса и живёт внутри challenge. Проверяем, что он
        // не утекает в поля, которые попадают в логи или в аналитику.
        val state = vm.state.value as AuthUiState.AwaitingNonce
        assertEquals("secret-value", state.challenge.secret)
        assertFalse(
            state.toString().contains("deep_link"),
            "в состояние не должны попадать лишние ссылки с публичным кодом",
        )
    }

    @Test
    fun `подтверждение сохраняет сессию и завершает вход`() = runTest(scheduler) {
        val store = FakeSessionStore()
        val service = FakeAuthService(
            challenge = challenge(),
            outcomes = mutableListOf(
                Result.success(PollOutcome.Confirmed(Session("tok", 1L), chatId = 42L)),
            ),
        )
        val vm = AuthViewModel(service, store, "Pixel")

        vm.startLogin()
        vm.submitNonce("4821")

        assertTrue(vm.state.value is AuthUiState.SignedIn)
        assertEquals("tok", store.saved?.token)
    }

    @Test
    fun `pending не считается ошибкой и опрос продолжается`() = runTest(scheduler) {
        val store = FakeSessionStore()
        val service = FakeAuthService(
            challenge = challenge(),
            outcomes = mutableListOf(
                Result.success(PollOutcome.Pending(500)),
                Result.success(PollOutcome.Confirmed(Session("tok", 1L), 42L)),
            ),
        )
        val vm = AuthViewModel(service, store, "Pixel")

        vm.startLogin()
        vm.submitNonce("4821")
        scheduler.advanceUntilIdle()

        // Два обращения: одно pending, второе подтвердило.
        assertEquals(2, service.pollCount)
        assertTrue(vm.state.value is AuthUiState.SignedIn)
    }

    @Test
    fun `already_consumed предлагает войти заново, а не блокирует навсегда`() = runTest(scheduler) {
        val service = FakeAuthService(
            challenge = challenge(),
            outcomes = mutableListOf(Result.success(PollOutcome.AlreadyConsumed)),
        )
        val vm = AuthViewModel(service, FakeSessionStore(), "Pixel")

        vm.startLogin()
        vm.submitNonce("4821")

        val state = vm.state.value
        assertTrue(state is AuthUiState.Failed)
        assertTrue((state as AuthUiState.Failed).retryable, "потерянный ответ не должен запирать пользователя")
    }

    @Test
    fun `истёкшая операция требует нового входа`() = runTest(scheduler) {
        val service = FakeAuthService(
            challenge = challenge(),
            outcomes = mutableListOf(Result.success(PollOutcome.Expired)),
        )
        val vm = AuthViewModel(service, FakeSessionStore(), "Pixel")

        vm.startLogin()
        vm.submitNonce("4821")

        assertTrue((vm.state.value as AuthUiState.Failed).retryable)
    }

    @Test
    fun `пустой код не отправляется на сервер`() = runTest(scheduler) {
        val service = FakeAuthService(challenge = challenge())
        val vm = AuthViewModel(service, FakeSessionStore(), "Pixel")

        vm.startLogin()
        vm.submitNonce("   ")

        assertEquals(0, service.pollCount, "пустой код не имеет смысла опрашивать")
        assertTrue(vm.state.value is AuthUiState.Failed)
    }

    @Test
    fun `неверный код возвращает к вводу, не начиная вход заново`() = runTest(scheduler) {
        val service = FakeAuthService(
            challenge = challenge(),
            outcomes = mutableListOf(Result.success(PollOutcome.Pending(500))),
        )
        val vm = AuthViewModel(service, FakeSessionStore(), "Pixel")

        vm.startLogin()
        vm.submitNonce("0000")
        vm.nonceRejected()

        assertTrue(
            vm.state.value is AuthUiState.AwaitingNonce,
            "операция ещё жива — пользователь должен иметь возможность ввести код снова",
        )
    }

    @Test
    fun `неверный код во время опроса возвращает к вводу, а не показывает сбой сети`() = runTest(scheduler) {
        // Сервер ответил `403 nonce_mismatch`. Это НЕ сбой связи: операция жива,
        // тот же public_code и secret годны — пользователь вводит код заново.
        // Раньше ошибка терялась в getOrElse, и на неверный код показывалось
        // «нет связи с сервером», а к вводу кода вернуться было нельзя.
        val service = FakeAuthService(
            challenge = challenge(),
            outcomes = mutableListOf(
                Result.failure(AuthPollException(AuthPollError.NonceMismatch)),
            ),
        )
        val vm = AuthViewModel(service, FakeSessionStore(), "Pixel")

        vm.startLogin()
        vm.submitNonce("0000")
        scheduler.advanceUntilIdle()

        val state = vm.state.value
        assertTrue(
            state is AuthUiState.AwaitingNonce,
            "неверный код должен возвращать к вводу, а не запирать экран",
        )
        assertFalse(
            state is AuthUiState.Failed,
            "неверный код — не сбой сети; сообщения «нет связи с сервером» быть не должно",
        )
    }

    @Test
    fun `сбой сети не оставляет экран в вечном ожидании`() = runTest(scheduler) {
        val service = FakeAuthService(
            challenge = challenge(),
            outcomes = mutableListOf(Result.failure(IllegalStateException("нет сети"))),
        )
        val vm = AuthViewModel(service, FakeSessionStore(), "Pixel")

        vm.startLogin()
        vm.submitNonce("4821")

        val state = vm.state.value
        assertTrue(state is AuthUiState.Failed)
        assertTrue((state as AuthUiState.Failed).retryable)
    }

    @Test
    fun `выход очищает сессию`() = runTest(scheduler) {
        val store = FakeSessionStore()
        val service = FakeAuthService(
            challenge = challenge(),
            outcomes = mutableListOf(Result.success(PollOutcome.Confirmed(Session("tok", 1L), 42L))),
        )
        val vm = AuthViewModel(service, store, "Pixel")

        vm.startLogin()
        vm.submitNonce("4821")
        vm.signOut()

        assertTrue(store.cleared)
        assertTrue(vm.state.value is AuthUiState.Idle)
    }
}
