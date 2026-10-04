package com.impossi8le.vpnapp.network

import com.impossi8le.vpnapp.domain.auth.AuthService
import com.impossi8le.vpnapp.domain.auth.LoginChallenge
import com.impossi8le.vpnapp.domain.auth.PollOutcome
import com.impossi8le.vpnapp.domain.auth.Session
import com.impossi8le.vpnapp.domain.config.ConfigFetchError
import com.impossi8le.vpnapp.domain.config.ConfigFetchException
import com.impossi8le.vpnapp.domain.config.ConfigList
import com.impossi8le.vpnapp.domain.config.ConfigService
import com.impossi8le.vpnapp.domain.config.ConfigSummary
import com.impossi8le.vpnapp.domain.config.FetchedConfig
import com.impossi8le.vpnapp.domain.config.SubscriptionStatus
import kotlinx.coroutines.delay

/**
 * Подставной бэкенд: имитирует СЕТЬ и АВТОРИЗАЦИЮ, чтобы проверять приложение без
 * живого сервера.
 *
 * **Что он имитирует и почему это допустимо.** Регистрация и вход по коду из
 * бота — это обмен с сервером; пока сервера нет, приложение нельзя провести по
 * сценарию входа иначе, как подставив ответы. Тестовый аккаунт захардкожен
 * осознанно: цель — пройти путь «вход → список конфигов → выбор → туннель», а не
 * проверять бэкенд.
 *
 * **Чего он НЕ делает и не должен.** Он не сообщает ничего о защите. Здесь нет
 * ни одного метода, способного вернуть «защищено»: статус защиты определяется
 * только замером на устройстве (§6). Подставной API, умеющий выдать зелёное,
 * обесценил бы весь инвариант — приложение показывало бы уверенность, за которой
 * ничего нет. Границу держим в типе: `ConfigService` и `AuthService` не имеют
 * доступа к `ProtectionVerdict`.
 *
 * Тестовый аккаунт: любой `device_nonce` из четырёх цифр подходит, `public_code`
 * выдаётся свой. Пароль не нужен — бот «подтверждает» сам.
 */
class MockApi(
    /** Задержка ответа, чтобы UI успел показать состояния. */
    private val latency: Long = 400,
) : AuthService, ConfigService {

    /** Какой профиль отдавать в `/config/{id}`. Ключи сюда не кладём никогда. */
    var profileBytes: ByteArray? = null

    private var issuedCode: String? = null

    override suspend fun startLogin(deviceName: String): Result<LoginChallenge> {
        delay(latency)
        val code = Crypto.newPublicCode()
        issuedCode = code
        return Result.success(
            LoginChallenge(
                publicCode = code,
                secret = Crypto.newSecret(),
                deepLink = "https://t.me/example_bot?start=login_$code",
                expiresAtEpochSeconds = nowSeconds() + 600,
            ),
        )
    }

    override suspend fun pollSession(
        publicCode: String,
        secret: String,
        deviceNonce: String,
    ): Result<PollOutcome> {
        delay(latency)

        // Код не тот, что выдавали — операция чужая или устарела.
        if (publicCode != issuedCode) return Result.success(PollOutcome.Expired)

        // Пустой или нецифровой код — как будто пользователь его не ввёл.
        if (deviceNonce.isBlank() || deviceNonce.any { !it.isDigit() }) {
            return Result.success(PollOutcome.Pending(retryAfterMillis = latency))
        }

        // Любые четыре цифры принимаются: это демо, а не проверка секрета.
        return Result.success(
            PollOutcome.Confirmed(
                session = Session(
                    token = "mock-session-${sha256(secret).take(12)}",
                    expiresAtEpochSeconds = nowSeconds() + 30L * 24 * 3600,
                ),
                chatId = TEST_CHAT_ID,
            ),
        )
    }

    override suspend fun listConfigs(): Result<ConfigList> {
        delay(latency)
        return Result.success(ConfigList(chatId = TEST_CHAT_ID, configs = mockConfigs()))
    }

    override suspend fun fetchConfig(configId: String): Result<FetchedConfig> {
        delay(latency)

        val summary = mockConfigs().firstOrNull { it.id == configId }
            ?: return Result.failure(ConfigFetchException(ConfigFetchError.NotFound))

        when (summary.status) {
            // Сервер не отдаёт конфиг по неактивной подписке — повторяем это
            // поведение, иначе приложение училось бы работать с тем, чего в
            // реальности не будет.
            SubscriptionStatus.EXPIRED ->
                return Result.failure(ConfigFetchException(ConfigFetchError.SubscriptionExpired))
            SubscriptionStatus.REVOKED ->
                return Result.failure(ConfigFetchException(ConfigFetchError.ConfigRevoked))
            else -> Unit
        }

        val body = profileBytes
            ?: return Result.failure(ConfigFetchException(ConfigFetchError.NetworkUnavailable))

        return Result.success(
            FetchedConfig(
                raw = body,
                version = "$configId-v1",
                hash = "sha256:" + sha256(body),
            ),
        )
    }

    /**
     * Список подключений для демонстрации: два рабочих и одно истёкшее.
     *
     * Истёкшее добавлено намеренно: экран обязан показать его отдельно и не
     * блокироваться. Список только из рабочих эту проверку бы не проводил.
     */
    private fun mockConfigs(): List<ConfigSummary> = listOf(
        ConfigSummary(
            id = "nl-ams-1",
            name = "Нидерланды · Амстердам",
            countryCode = "NL",
            city = "Амстердам",
            startDateEpochSeconds = nowSeconds() - 30L * 24 * 3600,
            endDateEpochSeconds = nowSeconds() + 60L * 24 * 3600,
            status = SubscriptionStatus.ACTIVE,
        ),
        ConfigSummary(
            id = "lv-riga-1",
            name = "Латвия · Рига",
            countryCode = "LV",
            city = "Рига",
            startDateEpochSeconds = nowSeconds() - 10L * 24 * 3600,
            endDateEpochSeconds = nowSeconds() + 80L * 24 * 3600,
            status = SubscriptionStatus.ACTIVE,
        ),
        ConfigSummary(
            id = "nl-rtm-1",
            name = "Нидерланды · Роттердам",
            countryCode = "NL",
            city = "Роттердам",
            startDateEpochSeconds = nowSeconds() - 200L * 24 * 3600,
            endDateEpochSeconds = nowSeconds() - 20L * 24 * 3600,
            status = SubscriptionStatus.EXPIRED,
        ),
    )

    private fun sha256(bytes: ByteArray): String = Crypto.sha256Hex(bytes)

    private fun sha256(text: String): String = Crypto.sha256Hex(text)

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private companion object {
        const val TEST_CHAT_ID = 123456789L
    }
}
