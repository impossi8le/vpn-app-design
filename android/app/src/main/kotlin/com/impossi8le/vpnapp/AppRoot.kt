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
import com.impossi8le.vpnapp.core.ui.UpdateUiState
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.feature.account.AboutScreen
import com.impossi8le.vpnapp.feature.account.ForceUpdateScreen
import com.impossi8le.vpnapp.feature.account.AccessRevokedScreen
import com.impossi8le.vpnapp.feature.account.AccountScreen
import com.impossi8le.vpnapp.feature.account.AccountScreenState
import com.impossi8le.vpnapp.feature.account.DefaultAccountScreenState
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

    // Настоящий вход состоялся — уходим на подключение.
    //
    // Переход живёт здесь, а не в экране ожидания: стек навигации виден только
    // внутри этого файла. Признак «вход состоялся» кладёт в состояние
    // MainActivity, узнав о нём от AuthViewModel; здесь мы лишь реагируем.
    //
    // Историю сбрасываем, а не копим: возврат на экран входа после состоявшегося
    // входа бессмысленен.
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) {
            stack.resetTo(AppDestination.Connection)
            destination = stack.current()
        }
    }

    // Выход состоялся — возвращаемся на вход.
    //
    // Туннель и профиль к этому моменту уже погашены (это сделал
    // `AppGraph.signOut()`), здесь остаётся только показать экран входа. Историю
    // сбрасываем: возврат на экран аккаунта после выхода бессмыслен и опасен —
    // там уже нет ни сессии, ни профиля.
    //
    // Признак — событие на один раз: сразу после перехода просим снять флаг.
    // Иначе повторный выход в том же сеансе оставил бы `signedOut` тем же `true`,
    // ключ эффекта не изменился бы — и на вход во второй раз мы бы не вернулись.
    LaunchedEffect(state.signedOut) {
        if (state.signedOut) {
            stack.resetTo(AppDestination.Login)
            destination = stack.current()
            onIntent(AppIntent.SignOutHandled)
        }
    }

    // Обязательное обновление перекрывает ВСЁ, включая навигацию.
    //
    // Проверка стоит здесь, а не отдельным маршрутом: маршрут можно выставить
    // неверно или уйти с него кнопкой «Назад», тогда как сборка ниже
    // поддерживаемой от этого не перестанет быть неподдерживаемой. Пока условие
    // истинно, ни один другой экран не рисуется — в том числе экран входа.
    val forced = state.update as? UpdateUiState.Available
    if (forced != null && forced.required) {
        ForceUpdateScreen(
            installedVersionName = state.build.versionName,
            update = forced,
            updateProgress = state.updateProgress,
            updateMessage = state.updateMessage,
            onDownloadUpdate = { onIntent(AppIntent.DownloadUpdate) },
            onCheckUpdate = { onIntent(AppIntent.CheckForUpdate) },
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(VpnColors.Void)) {
        when (destination) {

            AppDestination.Startup -> {
                // Проверка сессии: куда вести — на подключение или на вход.
                //
                // **Переход отложен, а не сделан прямо здесь.** Запись в
                // состояние во время самой композиции Compose не отрисовывает —
                // первая версия показывала из-за этого пустой экран. Поэтому
                // запускаем переход эффектом, ПОСЛЕ композиции, и в это время
                // показываем фон: пустой экран на мгновение — не мигание, а
                // честная заготовка.
                //
                // **Ключ эффекта — сам читаемый флаг `sessionRestored`.** Чтение
                // зашифрованной сессии идёт в эффекте (не в композиции), поэтому
                // на первом кадре значение ещё `null` — проверка не завершена.
                // Ключ по флагу даёт главное: пока он `null`, мы НИКУДА не уходим
                // (остаёмся на этом экране с фоном), а когда проверка завершится
                // и флаг станет `true`/`false`, эффект перезапустится по смене
                // ключа и поведёт правильно. Именно поэтому флаг троичный, а не
                // `Boolean = false`: с `false` по умолчанию эффект увёл бы на вход
                // по ещё не завершённой проверке, и восстановленная сессия
                // потерялась бы.
                LaunchedEffect(state.sessionRestored) {
                    val restored = state.sessionRestored ?: return@LaunchedEffect
                    stack.resetTo(
                        if (restored) AppDestination.Connection else AppDestination.Login,
                    )
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
                signingIn = state.signingIn,
                errorText = state.loginError,
                onSubmitNonce = { nonce -> onIntent(AppIntent.SubmitNonce(nonce)) },
                onReopenTelegram = { onIntent(AppIntent.StartLogin) },
                showDemoButton = state.showDemoButton,
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
                runningConfigName = state.runningConfigName,
                bypassCount = state.bypassCount,
                bypassSupported = state.bypassSupported,
                bypassConfigured = state.bypassConfigured,
                refreshing = state.refreshingConfigs,
                switchingWarning = state.switchingInProgress,
                startProgressText = state.startProgressText,
                notice = state.prepareError,
                onAction = { action -> onIntent(AppIntent.ConnectionAction(action)) },
                onOpenAccount = {
                    go(AppDestination.Account)
                },
                onRefreshConfigs = { onIntent(AppIntent.RefreshConfigs) },
                onSelectConfig = { id -> onIntent(AppIntent.SelectConfig(id)) },
                onSwitchCountry = { id -> onIntent(AppIntent.SwitchCountry(id)) },
                updateVersionCode = (state.update as? UpdateUiState.Available)
                    ?.takeUnless { state.updateBannerDismissed }?.versionCode,
                onDownloadUpdate = { onIntent(AppIntent.DownloadUpdate) },
                onDismissUpdateBanner = { onIntent(AppIntent.DismissUpdateBanner) },
            )

            AppDestination.Account -> AccountScreen(
                state = state.account,
                appVersion = state.build.versionName,
                onBack = { back() },
                onToggleTelegramId = { onIntent(AppIntent.ToggleTelegramId) },
                onConfirmCountrySwitchChange = { onIntent(AppIntent.SetConfirmCountrySwitch(it)) },
                onSupportChat = { onIntent(AppIntent.OpenSupportChat) },
                onSendDiagnostics = { onIntent(AppIntent.SendDiagnostics) },
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

            AppDestination.About -> AboutScreen(
                appVersion = state.build.versionName,
                buildSha = state.build.gitSha,
                update = state.update,
                updateProgress = state.updateProgress,
                updateMessage = state.updateMessage,
                onCheckUpdate = { onIntent(AppIntent.CheckForUpdate) },
                onDownloadUpdate = { onIntent(AppIntent.DownloadUpdate) },
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
/**
 * Что известно о самой сборке. Заполняется из `BuildConfig`, то есть из того,
 * чем сборку собрал CI (версия и SHA), — а не константой в коде.
 *
 * Отдельная структура, а не поля `AccountScreenState`: версия принадлежит
 * сборке, а не аккаунту, и лежать в двух местах она уже начинала разъезжаться.
 */
data class BuildInfo(
    val versionName: String,
    val versionCode: Int,
    val gitSha: String,
)

data class AppRootState(
    val status: ConnectionStatus = ConnectionStatus.Disconnected,
    val configs: List<ConfigRowState> = emptyList(),
    /**
     * Имя работающего подключения, если известно. Показывается на экране
     * подключения подзаголовком, когда туннель поднят, — пользователь просил
     * видеть «какой конфиг работает». `null` — имени нет (профиль не готов или
     * еще не выбран), тогда подзаголовок нейтрален. Источник — метаданные
     * установленного профиля (`ConfigManager.currentMeta().configId`),
     * сопоставленные со списком подключений; заполняет [MainActivity].
     */
    val runningConfigName: String? = null,
    /**
     * Число активных обходов. Больше нуля — часть трафика идёт мимо туннеля, и
     * экран подключения говорит об этом прямо, а не прячет за «Подключено».
     * Источник — файл обходов в `filesDir`, заполняемый [MainActivity].
     */
    val bypassCount: Int = 0,
    /**
     * Применяет ли это устройство исключения обходов. `false` на API < 33, где
     * `VpnService.Builder.excludeRoute` не существует: список обходов записан,
     * но маршруты не исключаются. Экран обязан отличать «обхода нет в списке»
     * от «обход есть, но не применяется» и не выдавать второе за первое.
     * Источник — `Build.VERSION.SDK_INT >= 33`, заполняет [MainActivity].
     *
     * **Дефолт `false`, а не `true`:** неизвестность склоняем в сторону «обход
     * не работает» — иначе забытый аргумент заставил бы экран обещать работающий
     * обход, которого может не быть. [MainActivity] всегда передаёт настоящее
     * значение, так что сегодня поведение не меняется; безопасна лишь сторона
     * умолчания для будущих вызывающих.
     */
    val bypassSupported: Boolean = false,
    /**
     * Задуманы ли обходы вовсе — независимо от версии Android и от того, сколько
     * маршрутов применилось. Список обходов пишется в файл сервису; непустой
     * список означает, что пользователь настроил обход, даже если на API < 33
     * применить его нечем (тогда [bypassSupported] = `false`).
     *
     * Отдельный вход, а не `bypassCount > 0`: применённое число на API < 33
     * всегда `0`, и выводить из него намерение значило бы снова потерять ветку
     * «обход задуман, но недоступен». Источник — тот же файл обходов, что и у
     * счётчика; заполняет [MainActivity].
     *
     * **Дефолт `false`** — безопасная сторона: не зная о намерении, экран не
     * упоминает обход, а не обещает его.
     */
    val bypassConfigured: Boolean = false,
    /**
     * Данные аккаунта для экрана «Аккаунт». Дефолт — [DefaultAccountScreenState]:
     * до ответа `/me` показывать нечего, а считать тут нечего и подавно.
     * Настоящие значения кладёт [MainActivity], когда приходит список из `/me`.
     */
    val account: AccountScreenState = DefaultAccountScreenState,
    /**
     * Данные сборки: версия, номер и SHA. Заполняет [MainActivity] из
     * `BuildConfig` — то есть из того, чем сборку собрал CI.
     */
    val build: BuildInfo = BuildInfo(
        versionName = "0.0.0",
        versionCode = 0,
        gitSha = "dev",
    ),
    /** Состояние проверки обновления. Показывается в «О сервисе» и баннером. */
    val update: UpdateUiState = UpdateUiState.Idle,
    /** Прогресс скачивания APK, `null` — не качаем. */
    val updateProgress: Int? = null,
    /**
     * Почему установка не началась или не удалась. Показывается в «О сервисе».
     * `null` — причины нет.
     */
    val updateMessage: String? = null,
    /** Баннер обновления закрыт пользователем. */
    val updateBannerDismissed: Boolean = false,
    /** Идёт проверка введённого кода: кнопка подтверждения занята. */
    val signingIn: Boolean = false,
    /** Текст ошибки входа, показываемый под полем кода. `null` — ошибки нет. */
    val loginError: String? = null,
    /** Вход состоялся: `AppRoot` уводит на экран подключения. */
    val signedIn: Boolean = false,
    /**
     * Итог восстановления сохранённой сессии при запуске (§3.3): `true` — токен
     * жив, экран входа пропускается; `false` — сессии нет/истекла, ведём на вход.
     *
     * **Троичный, а не `Boolean = false`.** `null` — проверка ещё не завершилась
     * (чтение зашифрованного хранилища идёт в эффекте, а не в композиции). Пока
     * `null`, `AppRoot` не уходит со `Startup` никуда — иначе дефолт `false`
     * увёл бы на вход раньше, чем стало известно, что сессия валидна. Значение
     * кладёт [MainActivity] по результату `AppGraph.restoreSession()`.
     */
    val sessionRestored: Boolean? = null,
    /** Выход состоялся: `AppRoot` возвращает на экран входа. */
    val signedOut: Boolean = false,
    /**
     * Показывать ли демонстрационный проход мимо входа.
     *
     * По умолчанию `false` — безопасная сторона: кнопка не появится, пока её
     * явно не включат. Включает её [MainActivity] по `BuildConfig.DEBUG`, так
     * что в release демонстрационного прохода нет.
     */
    val showDemoButton: Boolean = false,
    /**
     * Идёт перезагрузка списка подключений по «Обновить список».
     *
     * Отдельный флаг, а не вывод из пустоты списка: пустой список — это и
     * «подключений нет», и «ещё грузим», и различить их иначе нельзя. Пока
     * флаг поднят, экран говорит «идёт обновление…» — до правки нажатие на
     * кнопку вообще не давало видимой реакции.
     */
    val refreshingConfigs: Boolean = false,
    val switchingInProgress: Boolean = false,
    val startProgressText: String? = null,
    /**
     * Почему подключение не началось: нет активных подключений, подписка истекла,
     * доступ отозван, нет сети. `null` — причины нет.
     *
     * Отдельно от [switchingInProgress]: тот про окно без защиты при смене
     * страны, здесь же туннель просто не подняли.
     */
    val prepareError: String? = null,
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
    /** Проверить обновление вручную (кнопка в «О сервисе»). */
    data object CheckForUpdate : AppIntent

    /** Скачать и установить доступное обновление. */
    data object DownloadUpdate : AppIntent

    /** Убрать баннер обновления с главного экрана. */
    data object DismissUpdateBanner : AppIntent

    data object SessionChecked : AppIntent
    data object StartLogin : AppIntent

    /**
     * Пользователь ввёл код из бота и подтвердил.
     *
     * Ручной ввод — единственная защита входа от подмены (login-CSRF), поэтому
     * код идёт наверх как отдельное намерение, а не растворяется в состоянии
     * экрана.
     */
    data class SubmitNonce(val nonce: String) : AppIntent
    data object RefreshConfigs : AppIntent
    data object RefreshAccess : AppIntent
    data object SwitchTelegram : AppIntent
    data object ToggleTelegramId : AppIntent
    data object OpenSupportChat : AppIntent

    /** Открыть страницу загрузки: там версия, APK и как пройти Play Protect. */
    data object OpenDownloadPage : AppIntent
    data object OpenPrivacyPolicy : AppIntent
    data object OpenTerms : AppIntent
    /**
     * Собрать отчёт поддержке, скопировать его и открыть бота.
     *
     * Раньше звался `CopyErrorCode` и не был реализован вовсе — кнопка молчала.
     * Новое имя отражает, что действие делает с отчётом, а не откуда взялось
     * прежнее.
     */
    data object SendDiagnostics : AppIntent
    data object DeleteAccount : AppIntent
    data object SignOut : AppIntent

    /** Признак выхода отработан: `AppRoot` уже вернул пользователя на вход. */
    data object SignOutHandled : AppIntent
    data object RecheckVpnPermission : AppIntent
    data class ConnectionAction(val action: StatusAction) : AppIntent
    data class SelectConfig(val id: String) : AppIntent
    data class SwitchCountry(val id: String) : AppIntent
    data class SetConfirmCountrySwitch(val enabled: Boolean) : AppIntent
    data class SelectDemoConfig(val config: DemoConfig) : AppIntent
}