package com.impossi8le.vpnapp.testsupport

import com.impossi8le.vpnapp.domain.config.Profile
import com.impossi8le.vpnapp.domain.config.ProfileStore
import com.impossi8le.vpnapp.domain.config.StagedProfile
import com.impossi8le.vpnapp.domain.config.ProfileValidator
import java.io.File
import java.io.IOException

/**
 * Фейк НА ДИСКЕ, а не на `Map`.
 *
 * Это принципиально: протокол согласованной записи (§7) проверяется только тем,
 * что происходит с реальными файлами при реальной замене. Фейк на словаре
 * «сохраняет» и «читает» без участия файловой системы, поэтому не может
 * воспроизвести ни частичную запись, ни пропажу профиля, ни восстановление из
 * `.bak` — то есть именно те случаи, ради которых протокол и написан.
 *
 * [failOnWrite] — точка инъекции сбоя: позволяет проверить, что обрыв в любой
 * момент не оставляет систему без рабочего профиля.
 */
class FakeProfileStore(
    private val dir: File,
    private val validator: ProfileValidator = ProfileValidator { it.isNotEmpty() },
) : ProfileStore {

    /** Когда `true`, следующая замена файла падает как оборванная запись. */
    var failOnWrite: Boolean = false

    /** Сколько раз ломали запись — для проверки, что сценарий вообще состоялся. */
    var injectedFailures: Int = 0
        private set

    private val profile = File(dir, "profile.ovpn")
    private val backup = File(dir, "profile.ovpn.bak")
    private val pending = File(dir, "profile.ovpn.tmp")

    override fun load(): Profile? {
        if (profile.exists()) return Profile(profile.readBytes())
        if (backup.exists()) {
            // Самовосстановление: без него пропажа profile.ovpn остаётся навсегда.
            backup.copyTo(profile, overwrite = true)
            return Profile(profile.readBytes())
        }
        return null
    }

    override fun stage(raw: ByteArray): StagedProfile {
        require(validator.isValid(raw)) { "конфиг не прошёл валидацию" }
        pending.writeBytes(raw)
        return object : StagedProfile {}
    }

    override fun commit(staged: StagedProfile) {
        if (failOnWrite) {
            injectedFailures++
            // Обрыв ДО замены: новая версия не встала, старая ещё цела.
            throw IOException("симуляция обрыва записи")
        }
        if (profile.exists()) {
            profile.copyTo(backup, overwrite = true)
        }
        pending.copyTo(profile, overwrite = true)
        pending.delete()
    }

    override fun rollback() {
        if (backup.exists()) backup.copyTo(profile, overwrite = true)
    }

    override fun clear() {
        listOf(profile, backup, pending).forEach { if (it.exists()) it.delete() }
    }
}
