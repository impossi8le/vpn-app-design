package com.impossi8le.vpnapp

import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.domain.protection.ProtectionGate
import com.impossi8le.vpnapp.feature.auth.AuthUiState
import com.impossi8le.vpnapp.feature.auth.AuthViewModel
import com.impossi8le.vpnapp.feature.home.StatusAction
import com.impossi8le.vpnapp.feature.home.HomeViewModel
import com.impossi8le.vpnapp.feature.home.toRowStates
import com.impossi8le.vpnapp.tunnel.AppTunnelController
import com.impossi8le.vpnapp.tunnel.TunnelStatusReceiver
import com.impossi8le.vpnapp.vpnservice.VpnTunnelService
import java.io.File
import kotlinx.coroutines.launch

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
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .background(VpnColors.Void),
            ) {
                VpnApp()
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

    // Восстановление сохранённой сессии при запуске: `restoreSession()` вернёт
    // `true` и кладёт валидный токен в ApiClient; мы отражаем его в состоянии,
    // чтобы список подключений подтянулся без повторного входа.
    LaunchedEffect(Unit) {
        if (graph.restoreSession()) sessionToken = graph.apiClient.sessionToken
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

    // Список подключений — из живого /me. Ключ produceState — токен сессии,
    // прочитанный ИЗ Compose-состояния: без наблюдаемого ключа список пуст на
    // старте и не наполнился бы сам, когда вход завершится (иначе пользователю
    // пришлось бы перезаходить на экран). `emptyList()` в начале — честный
    // ответ, а не заглушка: при отсутствии токена сервер вернёт 401, и мы
    // покажем «войдите». Момент «сейчас» передаём внутрь преобразования явно,
    // чтобы подписи срока были детерминированы.
    val configs by produceState(initialValue = emptyList(), sessionToken) {
        val token = sessionToken ?: return@produceState
        graph.apiClient.sessionToken = token
        value = graph.configApi.listConfigs().getOrNull()
            ?.configs
            ?.toRowStates(System.currentTimeMillis() / 1000)
            .orEmpty()
    }

    AppRoot(
        state = AppRootState(
            status = status,
            configs = configs,
            switchingInProgress = switching,
            // Состояние входа приходит от AuthViewModel: экран ожидания показывает
            // «проверяем…»/ошибку, а AppRoot по `signedIn` уводит на подключение.
            signingIn = authState is AuthUiState.Polling,
            loginError = (authState as? AuthUiState.Failed)?.reason,
            signedIn = authState is AuthUiState.SignedIn,
        ),
        onIntent = { intent ->
            when (intent) {
                // Действия по матрице кнопки из макета.
                is AppIntent.ConnectionAction -> scope.launch {
                    when (intent.action) {
                        StatusAction.Connect -> viewModel.connect()

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

                // Ручной ввод кода из бота — единственная защита входа от
                // подмены. Код уходит в ViewModel, тот опрашивает сервер до
                // терминального состояния.
                is AppIntent.SubmitNonce -> authViewModel.submitNonce(intent.nonce)

                else -> Unit
            }
        },
    )
}
