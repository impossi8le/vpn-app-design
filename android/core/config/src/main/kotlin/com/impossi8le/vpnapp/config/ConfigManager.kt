package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ConfigFetchError
import com.impossi8le.vpnapp.domain.config.ConfigFetchException
import com.impossi8le.vpnapp.domain.config.ConfigService
import com.impossi8le.vpnapp.domain.config.FetchedConfig
import com.impossi8le.vpnapp.domain.config.Profile
import com.impossi8le.vpnapp.domain.config.ProfileMeta
import com.impossi8le.vpnapp.domain.config.ProfileMetaStore
import com.impossi8le.vpnapp.domain.config.ProfileStore
import java.security.MessageDigest

/**
 * Исход применения конфига. Отдельный тип, потому что UI реагирует по-разному:
 * «уже актуально» не ошибка и не повод что-то показывать, а «невалиден» —
 * повод сказать пользователю, что конфиг с сервера повреждён.
 */
sealed interface ApplyResult {
    /** Записано и проверено. `version` — версия установленного профиля. */
    data class Applied(val version: String) : ApplyResult

    /** Версия совпала с текущей — запись пропущена. */
    data class AlreadyCurrent(val version: String) : ApplyResult

    /** Конфиг не прошёл валидацию; старая версия на месте. */
    data object Rejected : ApplyResult

    /** Хеш после записи не совпал; выполнен откат. */
    data object HashMismatch : ApplyResult

    /**
     * Получить конфиг не удалось, и причина сохранена.
     *
     * Отдельный исход с вложенной причиной, а не сведение к [Rejected]:
     * «подписка истекла», «конфиг отозван» и «нет сети» требуют от экрана
     * разного, и раньше они были неразличимы (контракт §5).
     */
    data class FetchFailed(val error: ConfigFetchError) : ApplyResult
}

/**
 * Применение конфига с сервера — клиентская часть таблицы контракта §5.
 *
 * Порядок обязателен: скачать → проверить версию → `stage` (валидация) →
 * `commit` (атомарно) → сверить хеш. Каждый шаг защищает от своего отказа:
 *
 *  - версия бережёт от лишней записи, когда у клиента уже актуальное;
 *  - валидация не пускает мусор к записи, старая версия остаётся рабочей;
 *  - хеш ловит расхождение между тем, что пришло, и тем, что легло на диск
 *    (обрыв, ошибка файловой системы) — при расхождении откат.
 *
 * Хеш считается по телу ответа, а не по файлу на диске: смысл в том, чтобы
 * обнаружить расхождение между полученным и записанным. Поэтому значение
 * записывается до `commit`, а сверяется после.
 */
class ConfigManager(
    private val service: ConfigService,
    private val store: ProfileStore,
    private val meta: ProfileMetaStore,
) {

    /**
     * @param currentVersion версия уже установленного конфига, если есть.
     * @param endDateEpochSeconds срок подписки; фиксируется при любом успехе,
     *   чтобы клиент решал «пора удалять» по локальным данным.
     */
    suspend fun apply(
        configId: String,
        currentVersion: String?,
        endDateEpochSeconds: Long,
    ): ApplyResult {
        val result = service.fetchConfig(configId)
        if (result.isFailure) {
            // Причина сохраняется, а не сводится к «конфиг плохой»: экран обязан
            // различить истёкшую подписку, отозванный конфиг и недоступную сеть.
            val error = (result.exceptionOrNull() as? ConfigFetchException)?.error
                ?: ConfigFetchError.Unexpected(statusCode = 0)
            return ApplyResult.FetchFailed(error)
        }
        val fetched = result.getOrThrow()

        // Идемпотентность: та же версия — писать нечего. Но метаданные всё равно
        // фиксируем: срок подписки мог сдвинуться, и по нему решается «пора удалять».
        if (currentVersion != null && currentVersion == fetched.version) {
            meta.save(ProfileMeta(configId, fetched.version, endDateEpochSeconds))
            return ApplyResult.AlreadyCurrent(fetched.version)
        }

        val staged = try {
            store.stage(fetched.raw)
        } catch (_: IllegalArgumentException) {
            // Невалидный конфиг не дошёл до записи — старая версия цела.
            return ApplyResult.Rejected
        }

        store.commit(staged)

        if (!hashMatches(fetched)) {
            // Расхождение между полученным и записанным: возвращаем прежнюю
            // рабочую версию, а не оставляем подозрительный конфиг.
            store.rollback()
            return ApplyResult.HashMismatch
        }

        meta.save(ProfileMeta(configId, fetched.version, endDateEpochSeconds))
        return ApplyResult.Applied(fetched.version)
    }

    fun current(): Profile? = store.load()

    fun currentMeta(): ProfileMeta? = meta.load()

    /**
     * Сверка хеша.
     *
     * Сервер присылает `sha256:<hex>`. Если заголовка нет, сверять нечего —
     * это не повод считать конфиг испорченным.
     */
    private fun hashMatches(fetched: FetchedConfig): Boolean {
        // Пустой заголовок означает «сверять нечего» — это не ошибка конфига.
        if (fetched.hash.isEmpty()) return true

        // Префикс алгоритма срезается по ':' независимо от регистра: сервер
        // вправе прислать "sha256:" или "SHA256:", и это не порча конфига.
        val expected = fetched.hash.substringAfter(':', fetched.hash)
        return sha256Hex(fetched.raw).equals(expected, ignoreCase = true)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
