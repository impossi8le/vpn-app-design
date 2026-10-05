package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ConfigFetchError
import com.impossi8le.vpnapp.domain.config.ConfigService
import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.ProfileMetaStore
import com.impossi8le.vpnapp.domain.config.ProfileStore
import com.impossi8le.vpnapp.domain.config.ProfileUse
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import com.impossi8le.vpnapp.domain.config.decideProfileUse

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
 * Готовит профиль к подключению, по возможности НЕ обращаясь к серверу.
 *
 * Сначала смотрит на локальные метаданные: если профиль уже лежит и не истёк,
 * `GET /config/{id}` не делается вовсе. Сеть нужна, только когда профиля нет,
 * запрошен другой конфиг или срок вышел.
 *
 * `now` внедряется параметром ради детерминированного теста — та же причина,
 * что у `toRowState` в presentation.
 */
class ProfilePreparer(
    private val service: ConfigService,
    private val manager: ConfigManager,
    private val store: ProfileStore,
    private val metaStore: ProfileMetaStore,
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

        // Быстрый путь без сети: профиль уже на диске, он ОТ ЗАПРОШЕННОГО конфига
        // и срок не вышел. Список подключений не запрашиваем — иначе оффлайн-
        // пользователь с валидным профилем не поднял бы туннель (§6.2, §6.4 шаг 1).
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
                return PrepareResult.Ready
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
}
