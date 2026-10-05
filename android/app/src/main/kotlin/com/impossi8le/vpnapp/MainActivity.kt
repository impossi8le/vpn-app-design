package com.impossi8le.vpnapp

import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impossi8le.vpnapp.config.PrepareResult
import com.impossi8le.vpnapp.core.ui.UpdateUiState
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.domain.protection.ProtectionGate
import com.impossi8le.vpnapp.feature.auth.AuthUiState
import com.impossi8le.vpnapp.feature.auth.AuthViewModel
import com.impossi8le.vpnapp.feature.home.ConfigRowState
import com.impossi8le.vpnapp.feature.home.ConfigRowStatus
import com.impossi8le.vpnapp.feature.home.StatusAction
import com.impossi8le.vpnapp.feature.home.HomeViewModel
import com.impossi8le.vpnapp.feature.home.toRowStates
import com.impossi8le.vpnapp.tunnel.AppTunnelController
import com.impossi8le.vpnapp.tunnel.TunnelStatusReceiver
import com.impossi8le.vpnapp.update.ApkDownloader
import com.impossi8le.vpnapp.update.ApkInstaller
import com.impossi8le.vpnapp.update.UpdateChecker
import com.impossi8le.vpnapp.vpnservice.VpnTunnelService
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Через сколько после «Обновить список» повторить запрос «ещё раз потом».
 *
 * Пользователь просил именно таймер, а не мгновенный дубль: список на сервере
 * мог обновиться секундой позже. 15 секунд — достаточно, чтобы сервер успел, и
 * не настолько долго, чтобы человек решил, что кнопка ничего не делает: он уже
 * видит «идёт обновление…» с первого нажатия.
 */
private const val REFRESH_RETRY_DELAY_MS = 15_000L

/**
 * Точка входа.
 *
 * Здесь собирается composition root: пока это простые конструкторы, а не DI.
 * Полноценный DI появится, когда модулей с состоянием станет больше, — сейчас
 * он был бы абстракцией без потребителя.
 *
 * `launchMode="singleTask"` в манифесте нужен для deep link входа:
 * возврат из Telegram не должен создавать второй экземпляр активности.
 *
 * **Что сейчас в сборке.** Кнопка «Подключить» поднимает `VpnTunnelService`
 * через `AppTunnelController`. Состояние сервиса приходит широковещанием и
 * переводится в домен через `SystemState.toConnectionStatus()`, который
 * физически не умеет вернуть «защищено». Проба защиты пока неплатформенная и
 * сообщает `ProbeUnavailable`, поэтому экран честно покажет «защита не
 * подтверждена»: поднятый интерфейс не доказывает, что трафик пошёл через
 * него, и выдавать зелёное без замера — ровно та ложная уверенность, против
 * которой написан инвариант §6.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // targetSdk 35 принуждает edge-to-edge на Android 15+: без отступа
            // контент рисуется ПОД строкой статуса и панелью навигации, и
            // верхний ряд с нижней кнопкой оказываются перекрыты. Фон при этом
            // обязан заливать экран целиком — поэтому insets применяются
            // отступом к СОДЕРЖИМОМУ внутри Box, а не к Surface: панели
            // получают тёмную подложку приложения, а не системный цвет.
            Surface(
                // `color` задан явно, а не оставлен по умолчанию: у material3
                // Surface цвет по умолчанию — светлый `colorScheme.surface`
                // (0xFFFEF7FF). Пока Box заливал экран целиком, он перекрывал
                // эту подложку, и ошибки не было видно. После отступа insets Box
                // сжался контентом, и фон Surface проступил светлой полосой под
                // статус-баром. Поэтому и подложка здесь тёмная — та же, что у
                // контента.
                color = VpnColors.Void,
                modifier = Modifier
                    .fillMaxSize()
                    .background(VpnColors.Void),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    VpnApp()
                }
            }
        }
    }
}

@Composable
private fun VpnApp() {
    val context = LocalContext.current
    val tunnel = remember {
        AppTunnelController(
            context = context.applicationContext,
            serviceClass = VpnTunnelService::class.java,
            actionConnect = VpnTunnelService.ACTION_CONNECT,
            actionDisconnect = VpnTunnelService.ACTION_DISCONNECT,
            extraProfile = VpnTunnelService.EXTRA_PROFILE,
            // Путь к профилю в приватном каталоге приложения: сам текст с
            // приватным ключом через намерение не передаётся (см. сервис).
            profilePath = File(context.filesDir, "profile.ovpn").absolutePath,
        )
    }
    val viewModel: HomeViewModel = viewModel {
        HomeViewModel(tunnel, ProtectionGate(UnavailableProbe))
    }

    // Точка сборки backend-зависимостей: сеть, хранилища и координаторы живут
    // в AppGraph. Собираем ровно один раз на экран — пересоздание графа на
    // каждой рекомпозиции означало бы новый ApiClient и потерянный токен.
    val graph = remember { AppGraph(context.applicationContext, tunnel) }

    val authViewModel: AuthViewModel = viewModel {
        // Build.MODEL идёт в device_name: поддержка получает модель телефона,
        // а не пустую строку (закрывает расхождение №3 аудита).
        AuthViewModel(graph.authApi, graph.sessionStore, android.os.Build.MODEL)
    }
    val authState by authViewModel.state.collectAsState()

    val status by viewModel.status.collectAsState()
    val scope = rememberCoroutineScope()

    // Открываем Telegram, когда пришла ссылка. Показывать экран ожидания без
    // попытки открыть бота — значит оставить пользователя гадать, что дальше.
    //
    // startActivity из application-контекста требует FLAG_ACTIVITY_NEW_TASK;
    // если Telegram не установлен, запуск бросает ActivityNotFoundException —
    // его глушим, чтобы отсутствие мессенджера не роняло экран входа.
    //
    // Открытие привязано к КОНКРЕТНОМУ вызову входа (`publicCode`), а не к
    // факту состояния `AwaitingNonce`: при повороте экрана активность
    // пересоздаётся, ViewModel остаётся жива и `authState` остаётся тем же
    // `AwaitingNonce`. Без этой привязки Telegram открывался бы заново на
    // каждом повороте — неожиданно для пользователя. Запоминаем код через
    // `rememberSaveable`, чтобы пережить пересоздание активности.
    var openedPublicCode by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(authState) {
        val challenge = (authState as? AuthUiState.AwaitingNonce)?.challenge ?: return@LaunchedEffect
        if (challenge.publicCode == openedPublicCode) return@LaunchedEffect
        openedPublicCode = challenge.publicCode
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(challenge.deepLink),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // Токен сессии держим и в Compose-состоянии, и в общем ApiClient. Именно
    // запись в Compose-состояние запускает перезагрузку списка ниже: у обычного
    // `var` в ApiClient нет снапшот-наблюдателя, и запись в него не вызовет
    // рекомпозицию — список остался бы пустым до случайной перерисовки.
    var sessionToken by remember { mutableStateOf<String?>(null) }

    // Выход состоялся: `graph.signOut()` уже погасил туннель и стёр профиль с
    // сессией. Флаг ведёт на экран входа — навигация живёт в AppRoot, снаружи
    // туда не дотянуться, поэтому признак едет через состояние. Это событие на
    // один раз: AppRoot, вернув на вход, отвечает `SignOutHandled`, и флаг
    // снимается — иначе повторный выход не сработал бы.
    var signedOut by remember { mutableStateOf(false) }

    // Восстановление сохранённой сессии при запуске: `restoreSession()` вернёт
    // `true` и кладёт валидный токен в ApiClient; мы отражаем его в состоянии,
    // чтобы список подключений подтянулся без повторного входа.
    //
    // **Троичное состояние, а не `Boolean`.** Само чтение сессии —
    // `restoreSession()` трогает `EncryptedSharedPreferences` — в эффекте, а не
    // в композиции: на главном потоке это I/O, и делать его во время
    // композиции нельзя. Но тогда к первому кадру AppRoot ещё не знает ответа,
    // и `null` («ещё не проверено») — третий, честный вариант помимо
    // «восстановлена»/«нет». По этому признаку AppRoot держится на экране
    // `Startup`, пока ответ не пришёл, и лишь затем уходит на подключение или
    // на вход; с дефолтом `false` он увёл бы на вход раньше проверки.
    var sessionRestored by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        val restored = graph.restoreSession()
        if (restored) sessionToken = graph.apiClient.sessionToken
        sessionRestored = restored
    }

    // Сессия получена: токен кладём в общий ApiClient (его читают ConfigApi и
    // профиль) и в Compose-состояние — вторая запись и приводит к перезагрузке
    // списка. Сам переход на главный экран делает AppRoot по признаку
    // `signedIn` ниже: навигация живёт внутри AppRoot, снаружи туда не дотянуться.
    LaunchedEffect(authState) {
        val signedIn = authState as? AuthUiState.SignedIn ?: return@LaunchedEffect
        graph.apiClient.sessionToken = signedIn.token
        sessionToken = signedIn.token
    }

    // Диалог согласия на VPN показывает система, и показать его может только
    // активность. Контроллер живёт в application-контексте, поэтому отдаёт
    // намерение сюда, а экран запускает его и сообщает о полученном согласии.
    //
    // Без этого шага подключение молча не работало: ядро стартовало, печатало
    // свою версию и останавливалось — `Builder.establish()` без согласия
    // возвращает null, и интерфейс создать нечем.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            tunnel.onConsentGranted()
        }
    }

    DisposableEffect(tunnel) {
        tunnel.onConsentRequired = { intent -> consentLauncher.launch(intent) }
        onDispose { tunnel.onConsentRequired = null }
    }

    // Наблюдение привязано к жизненному циклу экрана: таймер перепроверки делает
    // сетевые обращения, и держать его вне экрана незачем.
    DisposableEffect(Unit) {
        // RECEIVER_NOT_EXPORTED: сервис в том же приложении, сторонним
        // приложениям слать сюда нечего. Без явного флага на Android 14
        // регистрация динамического приёмника падает.
        val receiver = TunnelStatusReceiver(tunnel)
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(TunnelStatusReceiver.ACTION_STATE)
                addAction(TunnelStatusReceiver.ACTION_NETWORK_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        viewModel.start()
        onDispose {
            context.unregisterReceiver(receiver)
            viewModel.stop()
        }
    }

    // Экраны собраны в AppRoot, а вся работа с туннелем осталась здесь: она
    // требует активности — системный диалог согласия и регистрацию приёмника.
    var showDemo by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }

    // Причина, по которой подключение не началось: профиль не готов. Показывается
    // на экране подключения отдельным блоком, а не молчанием кнопки — «нажал, и
    // ничего» читается как поломка.
    var prepareMessage by remember { mutableStateOf<String?>(null) }

    // Список подключений — из живого /me. Держим его в обычном состоянии, а не
    // в `produceState`: список обязан перезагружаться не только при смене
    // токена, но и по кнопке «Обновить список», и по таймеру после неё, а
    // `produceState` перезапускается лишь по смене ключа.
    var configs by remember { mutableStateOf(emptyList<ConfigRowState>()) }

    /** Идёт обновление списка: экран говорит это словами, а не молчит. */
    var refreshingConfigs by remember { mutableStateOf(false) }

    /**
     * Выбранное подключение.
     *
     * `rememberSaveable`, а не `remember`: выбор переживает пересоздание
     * активности. Полный перезапуск приложения он НЕ переживает — это осознанно,
     * хранить выбор на диске сейчас нечем без новой зависимости.
     */
    var selectedConfigId by rememberSaveable { mutableStateOf<String?>(null) }

    /** Корутина отложенного повтора после «Обновить список». Одна, не цикл. */
    var refreshRetry by remember { mutableStateOf<Job?>(null) }

    // --- Обновление приложения ---
    //
    // Проверка и установка живут здесь по той же причине, что и туннель:
    // установка APK требует активности — системный диалог запускается только
    // через ActivityResultLauncher.

    val updateChecker = remember { UpdateChecker(BuildConfig.VERSION_CODE, graph.updateApi) }
    val apkDownloader = remember { ApkDownloader(graph.apiClient.http, context.cacheDir) }
    val apkInstaller = remember { ApkInstaller(context) }

    var updateState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var updateProgress by remember { mutableStateOf<Int?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateBannerDismissed by remember { mutableStateOf(false) }

    // Огрызки прошлых закачек не должны переживать запуск: установщик может
    // принять за готовый APK половину файла.
    LaunchedEffect(Unit) { apkDownloader.clearStale() }

    // Проверка при запуске. Один раз, не в цикле: анонимный лимит запросов
    // GitHub невелик, а баннер подождёт.
    LaunchedEffect(Unit) {
        updateState = UpdateUiState.Checking
        updateState = updateChecker.check()
    }

    // Результат система показывает сама (диалог установки или сообщение об
    // ошибке). Здесь ловится только отказ запустить установщик.
    val installLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { /* результат установки система показывает сама */ }

    // Загрузка списка — одна на три случая: первичную загрузку при входе,
    // обновление по кнопке и отложенный повтор. Три копии разошлись бы.
    //
    // **Истёкшие подключения не показываем.** Выбирать в них нечего, и строка,
    // которую нельзя выбрать, — шум. Фильтр стоит здесь, где список
    // ПРОИЗВОДИТСЯ, а не в композабле: так он лежит рядом с источником данных и
    // не прячется в разметке. Момент «сейчас» передаём в преобразование явно —
    // подписи срока должны быть детерминированы.
    suspend fun loadConfigs() {
        val token = sessionToken ?: return
        graph.apiClient.sessionToken = token
        configs = graph.configApi.listConfigs().getOrNull()
            ?.configs
            ?.toRowStates(System.currentTimeMillis() / 1000)
            ?.filter { it.status == ConfigRowStatus.Available }
            .orEmpty()
    }

    // Первичная загрузка при получении токена. Ключ — сам токен: без
    // наблюдаемого ключа список остался бы пустым до случайной перерисовки,
    // когда вход завершится.
    LaunchedEffect(sessionToken) {
        if (sessionToken == null) return@LaunchedEffect
        loadConfigs()
    }

    // Показать выбранную строку выбранной. Статус Selected включает в ConfigRow
    // и рамку, и значок «Выбрано» — отдельного параметра экрану не нужно.
    // Копия строки, а не флаг: решение «что выбрано» приходит готовым, как и
    // «истекло», — экран по-прежнему решает только «как показать».
    val displayConfigs = remember(configs, selectedConfigId) {
        configs.map { row ->
            if (row.id == selectedConfigId) {
                row.copy(status = ConfigRowStatus.Selected)
            } else {
                row
            }
        }
    }

    AppRoot(
        state = AppRootState(
            status = status,
            configs = displayConfigs,
            refreshingConfigs = refreshingConfigs,
            switchingInProgress = switching,
            prepareError = prepareMessage,
            // Состояние входа приходит от AuthViewModel: экран ожидания показывает
            // «проверяем…»/ошибку, а AppRoot по `signedIn` уводит на подключение.
            signingIn = authState is AuthUiState.Polling,
            loginError = (authState as? AuthUiState.Failed)?.reason,
            signedIn = authState is AuthUiState.SignedIn,
            signedOut = signedOut,
            // Итог проверки сохранённой сессии: `null` пока не проверено,
            // `true` — AppRoot пропускает вход и ведёт на подключение (§3.3).
            sessionRestored = sessionRestored,
            // Демонстрационный проход — только в отладочной сборке. В release
            // `BuildConfig.DEBUG` == false, и кнопки на экране ожидания нет.
            // BuildConfig лежит в этом же пакете, отдельный импорт не нужен.
            showDemoButton = BuildConfig.DEBUG,
            // Версия и SHA — из BuildConfig, то есть из того, чем собрал CI.
            // Раньше на экранах стояла константа «1.0.0», расходившаяся со сборкой.
            build = BuildInfo(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                gitSha = BuildConfig.GIT_SHA,
            ),
            update = updateState,
            updateProgress = updateProgress,
            updateMessage = updateMessage,
            updateBannerDismissed = updateBannerDismissed,
        ),
        onIntent = { intent ->
            when (intent) {
                AppIntent.CheckForUpdate -> scope.launch {
                    updateState = UpdateUiState.Checking
                    updateMessage = null
                    updateState = updateChecker.check()
                }

                AppIntent.DownloadUpdate -> scope.launch {
                    // Ссылку берём заново: между проверкой и нажатием прошло
                    // время, и релиз мог обновиться.
                    val info = graph.updateApi.latestRelease().getOrNull()
                    if (info == null) {
                        updateMessage = "не удалось получить ссылку на обновление"
                        return@launch
                    }
                    if (!apkInstaller.canInstall()) {
                        // Не молчим и не показываем «скачано»: ведём туда, где
                        // разрешение включается — иначе пользователь упрётся в
                        // диалог, которого не будет.
                        updateMessage = "разрешите установку из этого источника"
                        runCatching { installLauncher.launch(apkInstaller.unknownSourcesIntent()) }
                        return@launch
                    }
                    updateMessage = null
                    updateProgress = 0
                    val file = apkDownloader.download(info.apkUrl) { updateProgress = it }.getOrNull()
                    updateProgress = null
                    if (file == null) {
                        updateMessage = "скачивание не удалось"
                        return@launch
                    }
                    runCatching { installLauncher.launch(apkInstaller.install(file)) }
                        .onFailure { updateMessage = "на устройстве нечем установить APK" }
                }

                AppIntent.DismissUpdateBanner -> updateBannerDismissed = true

                // Действия по матрице кнопки из макета.
                is AppIntent.ConnectionAction -> scope.launch {
                    when (intent.action) {
                        // Сначала профиль, потом туннель. Если профиль уже лежит
                        // и годен, ensureProfile НЕ обращается к сети — поднимаем
                        // из файла (см. ProfilePreparer). Только на Ready зовём
                        // connect: поднять интерфейс без файла профиля нельзя, а
                        // молчаливая кнопка хуже честной причины.
                        StatusAction.Connect -> {
                            // Прошлая причина не должна пережить новую попытку.
                            prepareMessage = null
                            when (val prepared = graph.preparer.ensureProfile()) {
                                PrepareResult.Ready -> viewModel.connect()
                                PrepareResult.NoActiveConfig -> prepareMessage =
                                    "Нет активных подключений"
                                PrepareResult.SubscriptionExpired -> prepareMessage =
                                    "Подписка истекла"
                                PrepareResult.Revoked -> prepareMessage =
                                    "Доступ к подключению отозван"
                                is PrepareResult.Failed -> prepareMessage = prepared.reason
                            }
                        }

                        // «Отменить» и «Отключить» — разные подписи, но действие
                        // одно: опустить то, что поднимается или уже поднято.
                        StatusAction.Cancel,
                        StatusAction.Disconnect,
                        -> viewModel.disconnect()

                        StatusAction.Retry -> viewModel.reverify()

                        StatusAction.OpenSettings -> Unit

                        StatusAction.RefreshAccess -> Unit
                    }
                }

                AppIntent.StartLogin -> authViewModel.startLogin()

                AppIntent.SignOut -> scope.launch {
                    // Порядок гашения — внутри `AppGraph.signOut()`: туннель →
                    // профиль с метаданными → сессия → токен в памяти. Здесь только
                    // вызов и очистка состояния входа; следом AppRoot уводит на вход.
                    graph.signOut()
                    authViewModel.signOut()
                    signedOut = true
                }

                // AppRoot уже вернул на вход — снимаем признак, чтобы следующий
                // выход в этом же сеансе снова считался новым событием.
                AppIntent.SignOutHandled -> signedOut = false

                // Ручной ввод кода из бота — единственная защита входа от
                // подмены. Код уходит в ViewModel, тот опрашивает сервер до
                // терминального состояния.
                is AppIntent.SubmitNonce -> authViewModel.submitNonce(intent.nonce)

                // «Обновить список». Раньше падало в `else -> Unit`, и кнопка
                // молчала. Теперь: показать «идёт обновление…», перезагрузить
                // список и ОДИН раз запросить ещё раз по таймеру — пользователь
                // просил именно отложенный повтор, а не мгновенный дубль.
                AppIntent.RefreshConfigs -> scope.launch {
                    refreshingConfigs = true
                    try {
                        loadConfigs()
                    } finally {
                        refreshingConfigs = false
                    }
                    // Прошлый повтор отменяем: два нажатия подряд не должны
                    // породить два независимых таймера.
                    refreshRetry?.cancel()
                    refreshRetry = scope.launch {
                        delay(REFRESH_RETRY_DELAY_MS)
                        loadConfigs()
                    }
                }

                // Выбор подключения: запомнить id и показать строку выбранной.
                // Профиль под это подключение ещё НЕ готовится — подготовка
                // идёт на «Подключить», и делать сеть здесь значило бы грузить
                // конфиг по одному тапу в список.
                is AppIntent.SelectConfig -> selectedConfigId = intent.id

                // «Поднять туннель на другом подключении». Минимально: тот же
                // выбор строки, что и у [SelectConfig]. Мы СОЗНАТЕЛЬНО не
                // трогаем туннель и не показываем «переключаюсь»: без
                // подготовленного профиля другого подключения поднимать нечего,
                // а фальшивый статус защиты запрещён инвариантом §6. Смена
                // страны меняет только выбор в списке.
                is AppIntent.SwitchCountry -> selectedConfigId = intent.id

                else -> Unit
            }
        },
    )
}
