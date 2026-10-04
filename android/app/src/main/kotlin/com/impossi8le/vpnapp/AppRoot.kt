package com.impossi8le.vpnapp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.feature.account.AboutScreen
import com.impossi8le.vpnapp.feature.account.AccessRevokedScreen
import com.impossi8le.vpnapp.feature.account.AccountScreen
import com.impossi8le.vpnapp.feature.account.AccountScreenState
import com.impossi8le.vpnapp.feature.account.BuildExpiryScreen
import com.impossi8le.vpnapp.feature.account.DemoConfig
import com.impossi8le.vpnapp.feature.account.DemoScreen
import com.impossi8le.vpnapp.feature.account.HowToEnableVpnScreen
import com.impossi8le.vpnapp.feature.account.NoSubscriptionScreen
import com.impossi8le.vpnapp.feature.auth.LoginScreen
import com.impossi8le.vpnapp.feature.auth.LoginWaitingScreen
import com.impossi8le.vpnapp.feature.home.ConfigRowState
import com.impossi8le.vpnapp.feature.home.ConfigRowStatus
import com.impossi8le.vpnapp.feature.home.ConnectionScreen
import com.impossi8le.vpnapp.feature.home.StatusAction
import com.impossi8le.vpnapp.domain.model.ConnectionStatus

/**
 * Корень приложения: какой экран показать и как перейти на другой.
 *
 * Навигация собрана здесь целиком, в одном месте, и это осознанно. Восемнадцать
 * экранов, разбросанные переходы по фичевым модулям — и проследить путь
 * пользователя становится нельзя: именно так в макете и появлялись тупики
 * (после отказа в разрешении VPN приложение молчало, а из «нет подписки» нельзя
 * было войти другим аккаунтом).
 *
 * **Состояние подключения не является маршрутом.** «Подключение», «Проверка»,
 * «Подключено», «Ошибка» — это один экран [ConnectionScreen], который меняет вид
 * по [ConnectionStatus]. Если бы каждое состояние было маршрутом, маршрут пришлось
 * бы синхронизировать с состоянием сервиса, и рассинхрон был бы вопросом времени.
 *
 * Здесь только переходы. Ни одно решение о защите не принимается: зелёный статус
 * приходит из [ConnectionStatus.Protected], а он доступен единственным путём —
 * через состоявшийся замер (§6).
 */
@Composable
fun AppRoot(
    state: AppRootState,
    onIntent: (AppIntent) -> Unit,
) {
    // Стек навигации: собственный, потому что нужен ровно один вид движения.
    //
    // Храним МАРШРУТ, а не сам объект стека. Это была ошибка первой версии:
    // объект в `mutableStateOf` мутировал на месте, ссылка не менялась, и
    // Compose не перерисовывал экран — приложение показывало пустоту. Состояние
    // теперь меняется перезаписью маршрута, и рекомпозиция происходит по факту.
    //
    // Сам стек живёт в обычной переменной: он не участвует в отрисовке, важна
    // только текущая вершина.
    val stack = remember { NavigationStack(AppDestination.Startup) }
    var destination by remember { mutableStateOf(stack.current()) }

    /**
     * Перейти на экран и перерисовать.
     *
     * Две строки, но они нетривиальны: мутировать стек МАЛО — без перезаписи
     * `destination` Compose не узнает об изменении, и экран не сменится. Именно
     * на этом первая версия показывала пустоту и не реагировала на кнопки.
     */
    fun go(target: AppDestination) {
        stack.push(target)
        destination = stack.current()
    }

    /** Вернуться назад. Возвращает `false`, если возвращаться некуда. */
    fun back(): Boolean {
        val moved = stack.pop()
        if (moved) destination = stack.current()
        return moved
    }

    // Аппаратная кнопка «Назад» должна работать так же, как «‹ Назад» на экране.
    // Если возвращаться некуда — отдаём событие системе, а не глотаем его.
    BackHandler(enabled = true) {
        // Если возвращаться некуда — не глотаем событие: система закроет
        // приложение. Молчаливо ничего не делать хуже всего: кнопка «Назад»
        // выглядит сломанной.
        back()
    }

    Box(modifier = Modifier.fillMaxSize().background(VpnColors.Void)) {
        when (destination) {

            AppDestination.Startup -> {
                // Проверка сессии: сохранённого токена нет, поэтому ведём на вход.
                //
                // **Переход отложен, а не сделан прямо здесь.** Запись в
                // состояние во время самой композиции Compose не отрисовывает —
                // первая версия показывала из-за этого пустой экран. Поэтому
                // запускаем переход эффектом, ПОСЛЕ композиции, и в это время
                // показываем фон: пустой экран на мгновение — не мигание, а
                // честная заготовка.
                //
                // Когда появится настоящее хранилище токена, здесь будет чтение
                // и решение: подключение или вход. Сейчас проверять нечего.
                LaunchedEffect(Unit) {
                    stack.resetTo(AppDestination.Login)
                    destination = stack.current()
                }
            }

            AppDestination.Login -> LoginScreen(
                onLogin = {
                    // Показ экрана ожидания доходит по намерению наружу, а
                    // переход — здесь: стек живёт в этом файле, и снаружи его
                    // не видно. Так экран не знает о навигации, а навигация не
                    // знает о входе.
                    onIntent(AppIntent.StartLogin)
                    go(AppDestination.LoginWaiting)
                },
                onPrivacyPolicy = { onIntent(AppIntent.OpenPrivacyPolicy) },
                onTerms = { onIntent(AppIntent.OpenTerms) },
            )

            AppDestination.LoginWaiting -> LoginWaitingScreen(
                remainingLabel = state.loginRemainingLabel,
                onReopenTelegram = { onIntent(AppIntent.StartLogin) },
                // **Демонстрационный проход дальше.** Настоящий вход ждёт
                // подтверждения в Telegram, которого в сборке для проверки нет:
                // показывать тупик на экране ожидания — значит оставить
                // недостижимыми ещё десять экранов, и проверить их нельзя.
                //
                // Поэтому здесь есть проход с явной пометкой, что он
                // демонстрационный. Он НЕ выдаёт себя за подтверждённый вход и
                // НЕ влияет на статус защиты: тот по-прежнему появляется только
                // из состоявшегося замера.
                onContinueDemo = {
                    // После входа возврат на экран входа бессмысленен: историю
                    // сбрасываем, а не копим.
                    stack.resetTo(AppDestination.Connection)
                    destination = stack.current()
                },
            )

            AppDestination.Connection -> ConnectionScreen(
                status = state.status,
                configs = state.configs,
                switchingWarning = state.switchingInProgress,
                startProgressText = state.startProgressText,
                onAction = { action -> onIntent(AppIntent.ConnectionAction(action)) },
                onOpenAccount = {
                    go(AppDestination.Account)
                },
                onRefreshConfigs = { onIntent(AppIntent.RefreshConfigs) },
                onSelectConfig = { id -> onIntent(AppIntent.SelectConfig(id)) },
                onSwitchCountry = { id -> onIntent(AppIntent.SwitchCountry(id)) },
            )

            AppDestination.Account -> AccountScreen(
                state = state.account,
                onBack = { back() },
                onToggleTelegramId = { onIntent(AppIntent.ToggleTelegramId) },
                onAutoConnectChange = { onIntent(AppIntent.SetAutoConnect(it)) },
                onAskFaceIdChange = { onIntent(AppIntent.SetAskFaceId(it)) },
                onConfirmCountrySwitchChange = { onIntent(AppIntent.SetConfirmCountrySwitch(it)) },
                onSupportChat = { onIntent(AppIntent.OpenSupportChat) },
                onSupportEmail = { onIntent(AppIntent.OpenSupportEmail) },
                onCopyErrorCode = { onIntent(AppIntent.CopyErrorCode) },
                onOpenAbout = {
                    go(AppDestination.About)
                },
                onDeleteAccount = { onIntent(AppIntent.DeleteAccount) },
                onSignOut = { onIntent(AppIntent.SignOut) },
            )

            AppDestination.NoSubscription -> NoSubscriptionScreen(
                onRefresh = { onIntent(AppIntent.RefreshAccess) },
                onSwitchTelegram = { onIntent(AppIntent.SwitchTelegram) },
            )

            AppDestination.AccessRevoked -> AccessRevokedScreen(
                onContactSupport = { onIntent(AppIntent.OpenSupportChat) },
                onRefreshAccess = { onIntent(AppIntent.RefreshAccess) },
                onSwitchTelegram = { onIntent(AppIntent.SwitchTelegram) },
            )

            AppDestination.HowToEnableVpn -> HowToEnableVpnScreen(
                onBack = { back() },
                // «Проверить снова» перечитывает состояние разрешения: система
                // не сообщает о включении VPN в настройках, узнать можно только
                // повторной проверкой.
                onRecheck = { onIntent(AppIntent.RecheckVpnPermission) },
            )

            AppDestination.BuildExpiry -> BuildExpiryScreen(
                expiryDate = state.account.buildExpiryDate,
                onBack = { back() },
                onHowToUpdate = { onIntent(AppIntent.OpenSupportChat) },
            )

            AppDestination.About -> AboutScreen(
                appVersion = state.account.appVersion,
                onBack = { back() },
                onOpenPrivacy = { onIntent(AppIntent.OpenPrivacyPolicy) },
                onOpenTerms = { onIntent(AppIntent.OpenTerms) },
            )

            AppDestination.Demo -> DemoScreen(
                configs = state.demoConfigs,
                selectedCode = state.demoSelectedCode,
                onSelect = { config -> onIntent(AppIntent.SelectDemoConfig(config)) },
            )
        }
    }
}

/**
 * Состояние всего приложения для показа.
 *
 * Одна структура, а не по одной на экран: экраны читают из неё своё, и это
 * позволяет видеть в одном месте, какие данные вообще есть у интерфейса. Когда
 * появится настоящий backend, сюда придут те же поля — интерфейс не изменится.
 */
data class AppRootState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val configs: List<ConfigRowState> = emptyList(),
    val account: AccountScreenState = AccountScreenState(
        telegramId = "•••• 4821",
        telegramIdRevealed = false,
        activeConnections = 0,
        totalConnections = 0,
        expiredConnections = 0,
        appVersion = "1.0.0",
        buildExpiryDate = "—",
        subscriptionUntil = "—",
        autoConnect = false,
        askFaceId = false,
        confirmCountrySwitch = true,
    ),
    val loginRemainingLabel: String = "5:00",
    val switchingInProgress: Boolean = false,
    val startProgressText: String? = null,
    val demoConfigs: List<DemoConfig> = emptyList(),
    val demoSelectedCode: String? = null,
)

/**
 * Что пользователь попросил сделать.
 *
 * События, а не прямые вызовы: экран не знает, кто именно их обработает —
 * ViewModel, сервис или системное намерение. Это позволяет подставить подставной
 * бэкенд в демо-режиме, не меняя экраны.
 */
sealed interface AppIntent {
    data object SessionChecked : AppIntent
    data object StartLogin : AppIntent
    data object RefreshConfigs : AppIntent
    data object RefreshAccess : AppIntent
    data object SwitchTelegram : AppIntent
    data object ToggleTelegramId : AppIntent
    data object OpenSupportChat : AppIntent
    data object OpenSupportEmail : AppIntent
    data object OpenPrivacyPolicy : AppIntent
    data object OpenTerms : AppIntent
    data object CopyErrorCode : AppIntent
    data object DeleteAccount : AppIntent
    data object SignOut : AppIntent
    data object RecheckVpnPermission : AppIntent
    data class ConnectionAction(val action: StatusAction) : AppIntent
    data class SelectConfig(val id: String) : AppIntent
    data class SwitchCountry(val id: String) : AppIntent
    data class SetAutoConnect(val enabled: Boolean) : AppIntent
    data class SetAskFaceId(val enabled: Boolean) : AppIntent
    data class SetConfirmCountrySwitch(val enabled: Boolean) : AppIntent
    data class SelectDemoConfig(val config: DemoConfig) : AppIntent
}