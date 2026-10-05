package com.impossi8le.vpnapp.config

import com.impossi8le.vpnapp.domain.config.ConfigList
import com.impossi8le.vpnapp.domain.config.ConfigService
import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.FetchedConfig
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus

/**
 * Сервис конфигов по сценарию. Параметризован ВХОДОМ (что вернуть), а не
 * выходом: иначе тест превратился бы в проверку самого фейка.
 */
class ScriptedConfigService(
    var listResult: Result<ConfigList> = Result.success(ConfigList(0L, emptyList())),
    var fetchResult: Result<FetchedConfig> = Result.failure(IllegalStateException("нет ответа")),
) : ConfigService {

    var listCount: Int = 0
        private set
    var fetchCount: Int = 0
        private set

    override suspend fun listConfigs(): Result<ConfigList> {
        listCount++
        return listResult
    }

    override suspend fun fetchConfig(configId: String): Result<FetchedConfig> {
        fetchCount++
        return fetchResult
    }
}

/** Одно активное подключение — частый вход для тестов подготовки профиля. */
fun activeConfig(
    id: String = "GEclient94",
    endDate: Long = 1_000_000L,
): ConfigSummary = ConfigSummary(
    id = id,
    name = "Германия · Франкфурт",
    countryCode = "DE",
    city = "Франкфурт",
    startDateEpochSeconds = 0L,
    endDateEpochSeconds = endDate,
    status = SubscriptionStatus.ACTIVE,
)
