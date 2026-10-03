package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.Profile
import com.impossi8le.vpnapp.domain.config.ProfileStore
import com.impossi8le.vpnapp.domain.config.StagedProfile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Протокол согласованной записи профиля (§7).
 *
 * Отличие от первоначальной формулировки в документе: `commit` делает **одно**
 * перемещение на месте, а не цепочку из двух. Два раздельных `rename` не
 * атомарны как пара — обрыв между ними (старая версия уже стала `.bak`, новая
 * ещё не встала) оставил бы приложение без `profile.ovpn`. Это ровно тот исход,
 * против которого написан §7.
 *
 * Плюс `load` самовосстанавливается из резерва. Явный `rollback` никто
 * автоматически не вызывает, поэтому без этого шага пропажа профиля была бы
 * необратимой.
 *
 * [failingMoves] — инъекция обрыва для тестов протокола; в продакшене всегда `false`.
 */
class FileProfileStore(
    private val dir: File,
    private val failingMoves: Boolean = false,
    private val validator: (ByteArray) -> Boolean = { it.isNotEmpty() },
) : ProfileStore {

    private val profile = File(dir, "profile.ovpn")
    private val backup = File(dir, "profile.ovpn.bak")
    private val pending = File(dir, "profile.ovpn.tmp")

    override fun load(): Profile? {
        if (profile.exists()) return Profile(profile.readBytes())

        // Самовосстановление. Профиль пропал, но предыдущая версия цела —
        // возвращаем её, иначе подключение сломано навсегда.
        if (backup.exists()) {
            move(backup, profile)
            return Profile(profile.readBytes())
        }
        return null
    }

    override fun stage(raw: ByteArray): StagedProfile {
        require(validator(raw)) { "конфиг не прошёл валидацию — до commit не доходит" }
        pending.writeBytes(raw)
        return object : StagedProfile {}
    }

    override fun commit(staged: StagedProfile) {
        if (failingMoves) throw IOException("симуляция обрыва записи")

        // Сначала сохраняем предыдущую версию, затем одним перемещением ставим
        // новую. Окна, в котором нет ни одной рабочей версии, не возникает.
        if (profile.exists()) move(profile, backup)
        move(pending, profile)
    }

    override fun rollback() {
        if (backup.exists()) {
            move(backup, profile)
        } else {
            // Откатывать нечего: это была ПЕРВАЯ запись, предыдущей версии нет.
            // «Вернуть прежнюю» здесь означает «вернуть отсутствие профиля»,
            // иначе неудачная первая запись остаётся на диске как рабочая.
            if (profile.exists()) profile.delete()
            if (pending.exists()) pending.delete()
        }
    }

    override fun clear() {
        listOf(profile, backup, pending).forEach { if (it.exists()) it.delete() }
    }

    /**
     * ATOMIC_MOVE, а не `File.renameTo`: последний молча откатывается на
     * копирование при переходе между файловыми системами, а копирование
     * атомарности не даёт вовсе.
     */
    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }
}
