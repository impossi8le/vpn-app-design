package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ConfigFetchError
import com.impossi8le.vpnapp.domain.config.ConfigService
import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.ProfileMeta
import com.impossi8le.vpnapp.domain.config.ProfileMetaStore
import com.impossi8le.vpnapp.domain.config.ProfileStore
import com.impossi8le.vpnapp.domain.config.ProfileUse
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import com.impossi8le.vpnapp.domain.config.decideProfileUse
import kotlinx.coroutines.withTimeoutOrNull

/** Итог подготовки профиля перед подключением. */
sealed interface PrepareResult {
    /** Профиль на диске и годен — можно поднимать туннель. */
    data object Ready : PrepareResult

    /** Ни одного действующего подключения. Не ошибка: пользователю нечего включать. */
    data object NoActiveConfig : PrepareResult

    /** Подписка кончилась: профиль удалён. */
    data object SubscriptionExpired : PrepareResult

    /** Доступ к конфигу отозван. */
    data object Revoked : PrepareResult

    /** Всё прочее: сеть, сессия, неожиданный ответ. */
    data class Failed(val reason: String) : PrepareResult
}

/**
 * Готовит профиль к подключению.
 *
 * **Профиль на диске перепроверяется на сервере, но офлайн-сценарий не ломается.**
 * Раньше валидный профиль отдавался без единого запроса. Это экономило сеть, но
 * держало пользователя на устаревшем профиле навсегда: адреса `remote` меняются,
 * когда платформа переставляет узлы, а клиент об этом не узнавал. На практике
 * люди неделями подключались к снятым адресам и видели `Server poll timeout` по
 * кругу, пока сервер уже отдавал свежий профиль.
 *
 * Теперь перед подключением делается одна ограниченная по времени попытка
 * обновления ([refreshTimeoutMillis]). Если она не удалась — нет сети, сервер
 * молчит, версия прежняя — поднимаем туннель из сохранённого профиля. Именно
 * поэтому проверка не может быть обязательной: офлайн-пользователь с годным
 * профилем обязан подключиться (§6.2, §6.4 шаг 1).
 *
 * `now` внедряется параметром ради детерминированного теста — та же причина,
 * что у `toRowState` в presentation.
 */
class ProfilePreparer(
    private val service: ConfigService,
    private val manager: ConfigManager,
    private val store: ProfileStore,
    private val metaStore: ProfileMetaStore,
    /**
     * Сколько ждать обновления профиля перед подключением.
     *
     * Потолок, а не срок: ответ приходит обычно быстрее, а провал этого окна —
     * не отказ, а повод идти с тем, что лежит на диске.
     */
    private val refreshTimeoutMillis: Long = DEFAULT_REFRESH_TIMEOUT_MILLIS,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /**
     * Подготовить профиль к подключению.
     *
     * @param configId конкретное подключение, для которого нужен профиль. `null`
     *   — «первое действующее», прежнее поведение при первичном подключении. При
     *   смене страны сюда приходит id выбранной строки: поднимать надо именно её,
     *   а не первую из списка.
     */
    suspend fun ensureProfile(configId: String? = null): PrepareResult {
        val hasProfile = store.load() != null
        val meta = metaStore.load()

        // Профиль уже на диске, он ОТ ЗАПРОШЕННОГО конфига и срок не вышел.
        // Список подключений (/me) не запрашиваем: он нужен только чтобы понять,
        // какой конфиг активен, а это уже известно из метаданных.
        //
        // Гард `configId == null || meta.configId == configId` обязателен: без него
        // валидный кэш ЧУЖОГО конфига (на диске A, просят B) вернул бы Ready и
        // туннель поднялся бы на прежней стране — ровно та ложь, против которой
        // написан весь этот класс.
        if (hasProfile && meta != null) {
            if (meta.endDateEpochSeconds <= now()) {
                store.clear()
                metaStore.clear()
                return PrepareResult.SubscriptionExpired
            }
            if (configId == null || meta.configId == configId) {
                // Не `Ready` напрямую: профиль мог устареть — адреса узлов
                // меняются на стороне платформы, и без этой попытки клиент
                // поднимал бы туннель к снятым адресам, пока сервер уже отдаёт
                // свежий. Провал попытки не отказ: идём с тем, что на диске.
                return refreshBeforeConnect(meta)
            }
        }

        val target = resolveTarget(configId) ?: return PrepareResult.NoActiveConfig

        when (decideProfileUse(hasProfile, meta, target.id, now())) {
            ProfileUse.Reusable -> return PrepareResult.Ready
            ProfileUse.Expired -> {
                // Профиль перестал работать по сроку — держать его незачем.
                store.clear()
                metaStore.clear()
                return PrepareResult.SubscriptionExpired
            }
            ProfileUse.Missing -> Unit
        }

        // Версия прежнего профиля годится только если он ОТ ТОГО ЖЕ конфига.
        // Иначе совпадение версий (а это серверные метки времени — коллизии
        // вероятны) заставит apply решить, будто запрошенный конфиг уже стоит,
        // и не записать его байты: туннель поднял бы чужой профиль как Ready.
        val storedVersion = meta?.takeIf { it.configId == target.id }?.version

        return when (val applied = manager.apply(target.id, storedVersion, target.endDateEpochSeconds)) {
            is ApplyResult.Applied, is ApplyResult.AlreadyCurrent -> PrepareResult.Ready
            ApplyResult.Rejected, ApplyResult.HashMismatch -> PrepareResult.Failed("конфиг отклонён")
            is ApplyResult.FetchFailed -> when (applied.error) {
                ConfigFetchError.SubscriptionExpired -> {
                    store.clear()
                    metaStore.clear()
                    PrepareResult.SubscriptionExpired
                }
                ConfigFetchError.ConfigRevoked -> PrepareResult.Revoked
                ConfigFetchError.Unauthorized -> PrepareResult.Failed("нужен повторный вход")
                ConfigFetchError.NetworkUnavailable -> PrepareResult.Failed("нет сети")
                ConfigFetchError.NotFound -> PrepareResult.Failed("конфиг не найден")
                ConfigFetchError.RateLimited -> PrepareResult.Failed("слишком часто — повторите позже")
                is ConfigFetchError.Unexpected -> PrepareResult.Failed("не удалось получить конфиг")
            }
        }
    }

    /**
     * Освежить профиль на диске, не отказывая в подключении из-за сети.
     *
     * **Почему исход «не успели» равен [PrepareResult.Ready].** Окно обновления —
     * потолок ожидания, а не условие подключения. Офлайн-пользователь с годным
     * профилем обязан поднять туннель (§6.2, §6.4 шаг 1); ждать 30-секундный
     * таймаут чтения ради проверки версии значило бы сломать именно этот сценарий.
     *
     * **Почему отказ обновления НЕ отказ подключения.** Свежая версия — благо, но
     * её отсутствие не делает сохранённый профиль недействительным. Исключения —
     * только явные приговоры сервера: подписка истекла и доступ отозван.
     */
    private suspend fun refreshBeforeConnect(meta: ProfileMeta): PrepareResult {
        val refreshed = withTimeoutOrNull(refreshTimeoutMillis) {
            manager.apply(meta.configId, meta.version, meta.endDateEpochSeconds)
        } ?: return PrepareResult.Ready

        return when (refreshed) {
            is ApplyResult.Applied, is ApplyResult.AlreadyCurrent -> PrepareResult.Ready

            // Мусор с сервера или расхождение хеша: прежний профиль на месте и
            // рабочий (apply откатывает), поднимать его честнее, чем не поднимать
            // ничего.
            ApplyResult.Rejected, ApplyResult.HashMismatch -> PrepareResult.Ready

            is ApplyResult.FetchFailed -> when (refreshed.error) {
                ConfigFetchError.SubscriptionExpired -> {
                    store.clear()
                    metaStore.clear()
                    PrepareResult.SubscriptionExpired
                }

                ConfigFetchError.ConfigRevoked -> PrepareResult.Revoked

                // Сеть, сессия, лимит запросов, пропавший конфиг — всё это не
                // приговор сохранённому профилю.
                else -> PrepareResult.Ready
            }
        }
    }

    /**
     * Какое подключение готовить.
     *
     * Конкретный [configId] берём из списка `/me` по id — так у нас есть и срок
     * подписки для метаданных, и подтверждение, что подключение вообще активно
     * (нельзя переключиться на истёкшее или отозванное). Если его в списке нет —
     * это НЕ «первое действующее»: молча подставить другую страну значило бы
     * переключить не туда, куда просили.
     *
     * `null` — прежнее поведение: первое действующее подключение.
     */
    private suspend fun resolveTarget(configId: String?): ConfigSummary? {
        val list = service.listConfigs().getOrElse { return null }
        val active = list.configs.filter { it.status == SubscriptionStatus.ACTIVE }
        return if (configId == null) {
            active.firstOrNull()
        } else {
            active.firstOrNull { it.id == configId }
        }
    }

    companion object {
        /**
         * Потолок ожидания обновления перед подключением.
         *
         * Пять секунд — компромисс: обычный ответ укладывается заметно быстрее,
         * а зависший запрос не должен превращать нажатие «Подключить» в молчание.
         */
        const val DEFAULT_REFRESH_TIMEOUT_MILLIS = 5_000L
    }
}
