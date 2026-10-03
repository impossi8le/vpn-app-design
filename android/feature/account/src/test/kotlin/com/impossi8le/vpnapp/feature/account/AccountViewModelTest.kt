package com.impossi8le.vpnapp.feature.account

import com.impossi8le.vpnapp.domain.auth.Session
import com.impossi8le.vpnapp.domain.auth.SessionStore
import com.impossi8le.vpnapp.domain.config.Profile
import com.impossi8le.vpnapp.domain.config.ProfileStore
import com.impossi8le.vpnapp.domain.config.StagedProfile
import com.impossi8le.vpnapp.testsupport.FakeTunnelControlling
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private class RecordingSessionStore(private var session: Session?) : SessionStore {
    override fun load(): Session? = session
    override fun save(session: Session) { this.session = session }
    override fun clear() { session = null }
}

private class RecordingProfileStore(private var profile: Profile?) : ProfileStore {
    var clearCount = 0
        private set

    override fun load(): Profile? = profile
    override fun stage(raw: ByteArray): StagedProfile = object : StagedProfile {}
    override fun commit(staged: StagedProfile) { profile = Profile(ByteArray(0)) }
    override fun rollback() {}
    override fun clear() { profile = null; clearCount++ }
}

/**
 * Выход обязан быть полным и в правильном порядке.
 *
 * Порядок не косметика: если профиль удалить раньше остановки туннеля, ядро
 * будет читать исчезнувший файл; если сессию раньше туннеля — останется
 * поднятое соединение, которым нечем управлять.
 */
class AccountViewModelTest {

    private fun session() = Session("tok", expiresAtEpochSeconds = 1_800_000_000L)
    private fun profile() = Profile("client\ndev tun\n".toByteArray())

    @Test
    fun `при отсутствии сессии экран показывает выход`() {
        val vm = AccountViewModel(RecordingSessionStore(null), RecordingProfileStore(null), FakeTunnelControlling())
        assertTrue(vm.state.value is AccountUiState.SignedOut)
    }

    @Test
    fun `при наличии сессии экран показывает вход`() {
        val vm = AccountViewModel(
            RecordingSessionStore(session()),
            RecordingProfileStore(profile()),
            FakeTunnelControlling(),
        )

        val state = vm.state.value
        assertTrue(state is AccountUiState.SignedIn)
        assertEquals(1_800_000_000L, (state as AccountUiState.SignedIn).expiresAtEpochSeconds)
    }

    @Test
    fun `выход очищает и сессию, и профиль`() = runTest {
        val sessions = RecordingSessionStore(session())
        val profiles = RecordingProfileStore(profile())
        val vm = AccountViewModel(sessions, profiles, FakeTunnelControlling())

        vm.signOut()

        assertNull(sessions.load(), "сессия обязана быть удалена")
        assertNull(profiles.load(), "профиль с приватным ключом не должен пережить выход")
        assertEquals(1, profiles.clearCount)
    }

    @Test
    fun `выход гасит туннель`() = runTest {
        val tunnel = FakeTunnelControlling()
        val vm = AccountViewModel(RecordingSessionStore(session()), RecordingProfileStore(profile()), tunnel)

        vm.signOut()

        assertEquals(1, tunnel.disconnectCount, "иначе соединение останется живым после «выхода»")
    }

    @Test
    fun `следующая сессия не подхватывает профиль предыдущего пользователя`() = runTest {
        // Главная причина, по которой профиль удаляется вместе с сессией.
        val profiles = RecordingProfileStore(profile())
        val sessions = RecordingSessionStore(session())
        val vm = AccountViewModel(sessions, profiles, FakeTunnelControlling())

        vm.signOut()
        sessions.save(Session("new-user", 1L))
        vm.refresh()

        assertNull(profiles.load(), "новый вход не должен видеть профиль прежнего пользователя")
    }

    @Test
    fun `состояние обновляется после выхода`() = runTest {
        val vm = AccountViewModel(RecordingSessionStore(session()), RecordingProfileStore(profile()), FakeTunnelControlling())
        assertTrue(vm.state.value is AccountUiState.SignedIn)

        vm.signOut()

        assertTrue(vm.state.value is AccountUiState.SignedOut)
    }

    @Test
    fun `выход идемпотентен`() = runTest {
        val profiles = RecordingProfileStore(profile())
        val vm = AccountViewModel(RecordingSessionStore(session()), profiles, FakeTunnelControlling())

        vm.signOut()
        vm.signOut()

        assertNull(profiles.load())
        assertTrue(vm.state.value is AccountUiState.SignedOut)
    }
}
