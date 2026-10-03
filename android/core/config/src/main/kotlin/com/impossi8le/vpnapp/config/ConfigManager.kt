package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ConfigService
import com.impossi8le.vpnapp.domain.config.FetchedConfig
import com.impossi8le.vpnapp.domain.config.Profile
import com.impossi8le.vpnapp.domain.config.ProfileStore
import java.security.MessageDigest

/**
 * Исход применения конфига. Отдельный тип, потому что UI реагирует по-разному:
 * «уже актуально» не ошибка и не повод что-то показывать, а «невалиден» —
 * повод сказать пользователю, что конфиг с сервера повреждён.
 */
sealed interface ApplyResult {
    /** Записано и проверено. */
    data object Applied : ApplyResult

    /** Версия совпала с текущей — запись пропущена. */
    data object AlreadyCurrent : ApplyResult

    /** Конфиг не прошёл валидацию; старая версия на месте. */
    data object Rejected : ApplyResult

    /** Хеш после записи не совпал; выполнен откат. */
    data object HashMismatch : ApplyResult
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
) {

    /**
     * @param currentVersion версия уже установленного конфига, если есть.
     */
    suspend fun apply(configId: String, currentVersion: String?): ApplyResult {
        val result = service.fetchConfig(configId)
        if (result.isFailure) return ApplyResult.Rejected
        val fetched = result.getOrThrow()

        // Идемпотентность: та же версия — писать нечего.
        if (currentVersion != null && currentVersion == fetched.version) {
            return ApplyResult.AlreadyCurrent
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

        return ApplyResult.Applied
    }

    fun current(): Profile? = store.load()

    /**
     * Сверка хеша.
     *
     * Сервер присылает `sha256:<hex>`. Если заголовка нет, сверять нечего —
     * это не повод считать конфиг испорченным.
     */
    private fun hashMatches(fetched: FetchedConfig): Boolean {
        val expected = fetched.hash.removePrefix("sha256:").takeIf { it.isNotEmpty() }
            ?: return true
        val actual = sha256Hex(fetched.raw)
        return actual.equals(expected, ignoreCase = true)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
