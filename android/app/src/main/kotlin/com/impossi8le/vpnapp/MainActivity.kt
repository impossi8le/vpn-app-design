package com.impossi8le.vpnapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import com.impossi8le.vpnapp.domain.settings.askBeforeCountrySwitch
import com.impossi8le.vpnapp.core.ui.UpdateUiState
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.domain.model.ConnectionStatus
import com.impossi8le.vpnapp.domain.protection.ProtectionGate
import com.impossi8le.vpnapp.domain.tunnel.BypassRoute
import com.impossi8le.vpnapp.feature.account.DefaultAccountScreenState
import com.impossi8le.vpnapp.feature.account.DiagnosticsInput
import com.impossi8le.vpnapp.feature.account.buildDiagnostics
import com.impossi8le.vpnapp.feature.account.toAccountScreenState
import java.time.Instant
import com.impossi8le.vpnapp.feature.auth.AuthUiState
import com.impossi8le.vpnapp.feature.auth.AuthViewModel
import com.impossi8le.vpnapp.feature.home.ConfigRowState
import com.impossi8le.vpnapp.feature.home.ConfigRowStatus
import com.impossi8le.vpnapp.feature.home.StatusAction
import com.impossi8le.vpnapp.feature.home.SwitchCountrySheet
import com.impossi8le.vpnapp.feature.home.HomeViewModel
import com.impossi8le.vpnapp.feature.home.toRowStates
import com.impossi8le.vpnapp.tunnel.AppTunnelController
import com.impossi8le.vpnapp.tunnel.TunnelStatusReceiver
import com.impossi8le.vpnapp.update.ApkDownloader
import com.impossi8le.vpnapp.update.ApkInstaller
import com.impossi8le.vpnapp.update.UpdateChecker
import com.impossi8le.vpnapp.update.UpdateVerdictStore
import com.impossi8le.vpnapp.update.restoredUpdateState
import com.impossi8le.vpnapp.vpnservice.VpnTunnelService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * Бот поддержки. Единственный канал: почту продукт не заводит, а Telegram —
 * тот же мессенджер, где выдаётся подписка, поэтому у пользователя он уже есть.
 *
 * `https://` (а не `tg://`): так ссылка открывается и в приложении Telegram, и
 * в браузере, если мессенджер не установлен, — один адрес вместо двух веток.
 */
private const val SUPPORT_BOT_URL = "https://t.me/FreeVPNHelp_bot"

/**
 * Страница загрузки приложения — человекочитаемый адрес, а не прямая ссылка на
 * файл. Пользователь должен видеть, что качает и какой версии, а не получать
 * APK из ссылки, которая выглядит как фишинг.
 *
 * Домен согласует владелец (см.
 * `docs/architecture/2026-10-06-distribution-owner-actions.md`, п. 1);
 * пока используется существующий хост сервиса.
 *
 * Порт `:4443` — тот же, что у базы API: без него ссылка мертва. Итоговый домен
 * остаётся за владельцем: `IP:порт` в ссылке для пользователя выглядит как
 * фишинг, поэтому это временный адрес, а не решение.
 */
private const val DOWNLOAD_PAGE_URL = "https://194-87-252-181.sslip.io:4443/app"

/** Файл отчёта в кеше приложения: переживает перезапуск, но чистится системой. */
private const val DIAGNOSTICS_FILE_NAME = "vpnapp-diagnostics.txt"

/**
 * Файл со списком обходов в приватном каталоге приложения.
 *
 * По строке `network/prefix` на обход — тот же формат, что читает сервис
 * (`applyBypassRoutes`). Путь к файлу, а не его содержимое, уезжает сервису:
 * файл живёт в приватном каталоге и недоступен другим приложениям.
 */
private const val BYPASS_FILE_NAME = "bypass-routes.txt"

/**
 * Короткая подпись состояния туннеля для отчёта.
 *
 * Класс [ConnectionStatus] не печатаем как есть: `Protected(evidence=…)` вытянул
 * бы содержимое evidence в текст отчёта. Нужна метка, а не дамп объекта. Это же
 * держит границу приватности — отчёт несёт ровно то, что здесь перечислено.
 */
private fun ConnectionStatus.label(): String = when (this) {
    ConnectionStatus.Disconnected -> "отключено"
    ConnectionStatus.Connecting -> "подключается"
    ConnectionStatus.VerifyingProtection -> "проверка защиты"
    is ConnectionStatus.Protected -> "защищено"
    is ConnectionStatus.ProtectionFailed -> "защита не подтверждена"
    is ConnectionStatus.Failed -> "ошибка: $reason"
}

/**
 * Открыть бота поддержки в Telegram.
 *
 * `FLAG_ACTIVITY_NEW_TASK` обязателен: запускаем с application-контекста, у
 * которого нет своей задачи, — без флага система бросит исключение.
 * `runCatching` — потому что Telegram (или любой обработчик ссылки) может
 * отсутствовать: отсутствие мессенджера не повод ронять клиент VPN.
 */
private fun openSupportBot(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(SUPPORT_BOT_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** Открыть страницу загрузки в браузере. Браузера может не быть — не роняем. */
private fun openDownloadPage(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(DOWNLOAD_PAGE_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Открыть системные настройки VPN.
 *
 * `ACTION_VPN_SETTINGS` есть не на всякой прошивке; общий экран настроек —
 * честный запасной вариант, а не молчание.
 */
private fun openVpnSettings(context: Context) {
    val intent = Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/**
 * Загрузить список обходов и записать его в файл перед подключением.
 *
 * Отсутствие списка не мешает подключению: неудача загрузки — пустой список
 * (см. [BypassRoutesApi]), и тогда файл просто пуст, а сервис поднимает туннель
 * без исключений. Формат строки — `network/prefix`, тот же, что читает сервис.
 * Запись — на IO: домашний каталог приложения читается/пишется не мгновенно.
 */
private suspend fun writeBypassFile(context: Context, routes: List<BypassRoute>) {
    val body = routes.joinToString("\n") { "${it.network}/${it.prefixLength}" }
    withContext(Dispatchers.IO) {
        runCatching { File(context.filesDir, BYPASS_FILE_NAME).writeText(body) }
    }
}

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
            extraBypass = VpnTunnelService.EXTRA_BYPASS_FILE,
            // Тот же файл, что наполняет connectThroughBypass перед подключением.
            // Путь передаём всегда — пустой файл сервис читает как «обходов нет».
            bypassPath = File(context.filesDir, BYPASS_FILE_NAME).absolutePath,
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

    // Причина, по которой подключение не началось: профиль не готов. Показывается
    // на экране подключения отдельным блоком, а не молчанием кнопки — «нажал, и
    // ничего» читается как поломка.
    //
    // Объявлено здесь, а не рядом с остальными полями экрана: на эту переменную
    // пишет и лямбда согласия ниже (отказ в системном диалоге), а локальное
    // объявление обязано стоять ДО первого чтения/записи — иначе компилятор
    // отвергнет ссылку вперёд.
    var prepareMessage by remember { mutableStateOf<String?>(null) }

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
        } else {
            // Пользователь закрыл системный диалог согласия. Без него туннель не
            // поднять, и молчание выглядело бы как «кнопка не работает».
            prepareMessage =
                "Нужно разрешить подключение VPN. Нажмите «Подключить» ещё раз или откройте настройки."
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

    /**
     * Открытый шит подтверждения смены страны.
     *
     * `pendingSwitchCountry` — имя целевого подключения; `null` — шит закрыт.
     * Держим имя (а не id), потому что шиту нужен человеческий текст заголовка,
     * а не идентификатор; сам id — рядом, в [pendingSwitchId].
     */
    var pendingSwitchCountry by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingSwitchId by rememberSaveable { mutableStateOf<String?>(null) }

    /** Значение галочки «Больше не спрашивать» в открытом шите. */
    var pendingSwitchDontAsk by remember { mutableStateOf(false) }

    /**
     * Поколение переключения.
     *
     * Пользователь может запросить переключение, не дождавшись предыдущего.
     * Каждому запуску выдаётся свой номер; завершившаяся работа сверяет его и,
     * если номер устарел, ничего не пишет — иначе старая задача перебила бы
     * состояние новой (в том числе сняла бы флаг «идёт переключение» не вовремя).
     */
    var switchToken by remember { mutableStateOf(0) }
    var switchingJob by remember { mutableStateOf<Job?>(null) }

    // Список подключений — из живого /me. Держим его в обычном состоянии, а не
    // в `produceState`: список обязан перезагружаться не только при смене
    // токена, но и по кнопке «Обновить список», и по таймеру после неё, а
    // `produceState` перезапускается лишь по смене ключа.
    var configs by remember { mutableStateOf(emptyList<ConfigRowState>()) }

    // Данные экрана «Аккаунт» — та же природа, что у списка: приходят из `/me`
    // и перезагружаются вместе с ним. Хранятся отдельным состоянием, потому что
    // экран аккаунта показывает их в другом месте и в другом виде, а не строкой
    // подключения. Стартуем с умолчания экрана, пока ответа нет.
    var account by remember { mutableStateOf(DefaultAccountScreenState) }

    /**
     * Тумблер «Подтверждать смену страны».
     *
     * Источник — несекретное хранилище настроек, а не сервер и не состояние
     * экрана: это предпочтение устройства, и оно переживает перезапуск. Раньше
     * тумблер показывал константу и ни на что не влиял — `SetConfirmCountrySwitch`
     * не обрабатывался вовсе.
     */
    var confirmCountrySwitch by remember { mutableStateOf(graph.settings.confirmCountrySwitch) }

    /** «Больше не спрашивать» — переживает перезапуск так же, как тумблер. */
    var dontAskCountrySwitch by remember { mutableStateOf(graph.settings.dontAskCountrySwitch) }

    // Тумблер в `account` синхронизируем с хранилищем ПОСЛЕ каждой перезагрузки
    // `/me`: `toAccountScreenState` переносит поле из `base` как есть, а `base` —
    // экранный дефолт с «включено». Без этой строки выключенный пользователем
    // тумблер возвращался бы к «включено» при первом обновлении списка.
    LaunchedEffect(account, confirmCountrySwitch) {
        if (account.confirmCountrySwitch != confirmCountrySwitch) {
            account = account.copy(confirmCountrySwitch = confirmCountrySwitch)
        }
    }

    /**
     * Проверка «не показывать шит повторно».
     *
     * `true` после того, как `SwitchCountrySheet` пробыл в разметке кадр:
     * `pendingSwitchCountry` переживает пересоздание активности (`rememberSaveable`),
     * а галочка — нет, и без этой проверки закрытый шит всплыл бы снова после
     * поворота. Один раз показали — либо подтвердили, либо отменили; состояние
     * шита больше не восстанавливаем.
     */
    var switchingChecked by remember { mutableStateOf(false) }

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

    val updateChecker = remember {
        // enforceMinSupported = !DEBUG: отладочные сборки идут с versionCode = 1,
        // и порог поддерживаемых версий запер бы разработку вне приложения.
        UpdateChecker(
            currentVersionCode = BuildConfig.VERSION_CODE,
            service = graph.updateApi,
            enforceMinSupported = !BuildConfig.DEBUG,
        )
    }
    val apkDownloader = remember { ApkDownloader(graph.apiClient.http, context.cacheDir) }
    val apkInstaller = remember { ApkInstaller(context) }

    val verdictStore = remember { UpdateVerdictStore(File(context.filesDir, "update-verdict.txt")) }

    // Стартовое состояние — ИЗ ПАМЯТИ, а не Idle.
    //
    // Проверено на телефоне: с Idle приложение на холодном старте успевало
    // показать рабочий экран, пока шла сетевая проверка, и блокировку можно
    // было обойти, просто вернувшись в приложение. Блокировка обязана
    // действовать с первого кадра, поэтому прошлый вердикт читается здесь же,
    // до первой композиции.
    var updateState by remember {
        mutableStateOf(restoredUpdateState(BuildConfig.VERSION_CODE, verdictStore.read(), !BuildConfig.DEBUG))
    }
    var updateProgress by remember { mutableStateOf<Int?>(null) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateBannerDismissed by remember { mutableStateOf(false) }

    // Огрызки прошлых закачек не должны переживать запуск: установщик может
    // принять за готовый APK половину файла.
    LaunchedEffect(Unit) { apkDownloader.clearStale() }

    // Проверка при запуске. Один раз, не в цикле: анонимный лимит запросов
    // GitHub невелик, а баннер подождёт.
    LaunchedEffect(Unit) {
        updateState = updateChecker.check()
        // Вердикт запоминается, чтобы блокировка не ждала сеть при следующем
        // запуске. Состояние Failed не пишем: оно про сеть, а не про версии.
        val available = updateState as? UpdateUiState.Available
        if (available != null) {
            verdictStore.write(latest = available.versionCode, minSupported = graph.updateApi.minSupported())
        }
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
        val list = graph.configApi.listConfigs().getOrNull()
        // Экран «Аккаунт» питается тем же ответом `/me`, что и список: сервер
        // отдаёт там лимит устройств и срок подписки, и без этого экран показывал
        // «0 из 0» и «—». Считаем ДО фильтра — «N истекли» должно видеть и
        // истёкшие строки, которые из списка подключений убраны.
        if (list != null) {
            account = list.toAccountScreenState(account)
        }
        configs = list
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

    // Имя работающего подключения — честный источник — метаданные установленного
    // профиля: именно тот configId, что лежит в файле, отдан туннелю. Читаем его
    // в эффекте, а не в композиции: `currentMeta()` трогает файл, и на главном
    // потоке во время композиции это I/O. Сопоставляем id со списком, чтобы
    // показать человеческое имя, а не идентификатор; если такого подключения в
    // списке уже нет (истекло, отозвано) — падаем на имя выбранного, и лишь
    // затем на `null`. Пересчитываем по смене статуса: профиль пишется перед
    // поднятием туннеля, поэтому к моменту «Подключено» метаданные уже готовы.
    var runningConfigName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(status, configs, selectedConfigId) {
        val runningId = graph.configManager.currentMeta()?.configId
        runningConfigName = configs.firstOrNull { it.id == runningId }?.name
            ?: configs.firstOrNull { it.id == selectedConfigId }?.name
    }

    /**
     * Число активных обходов — ПРИМЕНЁННЫХ сервисом, а не записанных в файл.
     *
     * Раньше здесь считались строки файла обходов. Это число могло быть больше
     * применённого: часть строк бывает битой, а на API < 33 `excludeRoute` не
     * существует. Показать число строк — значит заявить об обходе, которого на
     * устройстве нет: недоказанное утверждение, недопустимое по §6. Источник
     * теперь — сам сервис: он кладёт в широковещание состояния счётчик
     * состоявшихся исключений (см. `applyBypassRoutes`), контроллер отдаёт его
     * потоком, а мы читаем. Дефолт `0` — безопасная сторона.
     */
    val bypassCount by tunnel.appliedBypass.collectAsState()

    // Применяет ли это устройство исключения обходов. Считаем один раз здесь,
    // у источника данных об обходах, и передаём вниз вместе со счётчиком: иначе
    // проверка версии расползлась бы по экранам. `excludeRoute` есть только с
    // Android 13 (API 33); при minSdk 26 на API 26–32 файл обходов лежит, но
    // маршруты не исключаются — и экран обязан это сказать, а не обещать обход.
    val bypassSupported = Build.VERSION.SDK_INT >= 33

    /**
     * Задуманы ли обходы вовсе — по числу строк в записанном файле обходов.
     *
     * Это НАМЕРЕНИЕ, а не результат: список непуст даже там, где устройство не
     * может применить ни одного маршрута (API < 33). Отдельный вход нужен
     * именно поэтому: `appliedBypass` на таких устройствах всегда `0`, и вывести
     * из него «пользователь настраивал обход» нельзя — иначе экран снова
     * потеряет ветку «обход недоступен на этой версии Android».
     *
     * Источник — сам файл, а не только что полученный из сети список: при
     * перезапуске приложения списка в памяти нет, а файл с обходами уже лежит.
     * Пересчёт — по смене статуса: файл пишется перед подключением; чтение на IO.
     * Дефолт `false` — безопасная сторона: не зная о намерении, экран молчит об
     * обходе, а не обещает его.
     */
    var bypassConfigured by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        val bypassFile = File(context.filesDir, BYPASS_FILE_NAME)
        bypassConfigured = withContext(Dispatchers.IO) {
            runCatching { bypassFile.readLines().any { it.isNotBlank() } }.getOrDefault(false)
        }
    }

    /**
     * Подтверждённое переключение на другое подключение.
     *
     * Порядок обязателен и повторяет ручной путь: подготовить профиль целевого
     * конфига (при готовом кэше сети не трогаем), затем переподнять туннель.
     * `disconnect` + `connect` — а не «reconnect»: отдельного метода переключения
     * у движка нет, и придумывать его сейчас значило бы обещать больше, чем есть.
     *
     * Флаг `switching` (он же `switchingWarning` на экране) поднимается на время
     * и снимается ВСЕГДА — и при отказе тоже: иначе экран навсегда остался бы в
     * состоянии «идёт переключение». Токен поколения гасит гонку: если за время
     * переключения пользователь запустил другое, устаревшая работа не пишет
     * состояние (сверив токен) — иначе она сняла бы флаг у актуальной.
     */
    fun performSwitch(targetId: String) {
        switchingJob?.cancel()
        val token = ++switchToken
        switchingJob = scope.launch {
            switching = true
            prepareMessage = null
            try {
                when (val prepared = graph.preparer.ensureProfile(targetId)) {
                    PrepareResult.Ready -> {
                        // Опускаем текущий туннель, чтобы он не читал файл, который
                        // вот-вот подменится новым профилем.
                        viewModel.disconnect()
                        // Обходы к новому профилю: файл пишем перед connect по той
                        // же причине, что и в ветке Connect (см. ниже).
                        writeBypassFile(context, graph.bypassRoutes.routes())
                        viewModel.connect()
                        selectedConfigId = targetId
                    }
                    PrepareResult.NoActiveConfig -> prepareMessage = "Нет активных подключений"
                    PrepareResult.SubscriptionExpired -> prepareMessage = "Подписка истекла"
                    PrepareResult.Revoked -> prepareMessage = "Доступ к подключению отозван"
                    is PrepareResult.Failed -> prepareMessage = prepared.reason
                }
            } finally {
                // Снимаем флаг только если это по-прежнему наше поколение: иначе
                // устаревшая работа погасила бы индикатор актуальной.
                if (token == switchToken) switching = false
            }
        }
    }

    // Шит подтверждения смены страны рисуется ПОВЕРХ экранов, поэтому лежит в
    // том же Box, что и AppRoot, и ПОСЛЕ него: в Compose верхний слой — последний
    // потомок, и поставленный до AppRoot шит оказался бы под ним.
    Box(modifier = Modifier.fillMaxSize()) {
    AppRoot(
        state = AppRootState(
            status = status,
            configs = displayConfigs,
            // Имя работающего подключения: см. LaunchedEffect выше. Нужно экрану
            // подключения, чтобы подзаголовком показать «какой конфиг работает»
            // при поднятом туннеле.
            runningConfigName = runningConfigName,
            // Число применённых обходов (не строк в файле): см. `appliedBypass`
            // выше. Нужно экрану подключения, чтобы при активных обходах честно
            // сказать, что часть трафика идёт мимо туннеля, а не молчать под
            // «Подключено».
            bypassCount = bypassCount,
            // Поддерживает ли эта версия Android исключения обходов: см. выше.
            bypassSupported = bypassSupported,
            // Задуманы ли обходы вовсе (непустой файл): см. `bypassConfigured`
            // выше. Нужно экрану, чтобы не спутать «обходов нет» с «обход
            // задуман, но не применён»: применённое число на API < 33 всегда 0,
            // и намерение приходится везти отдельным входом.
            bypassConfigured = bypassConfigured,
            // Данные экрана «Аккаунт»: заполняются из `/me` в loadConfigs. Здесь —
            // то, что успело прийти (или умолчание, если ответа ещё нет).
            account = account,
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
                                PrepareResult.Ready -> {
                                    // Обходы тянем ПЕРЕД connect: файл должен
                                    // быть на диске к моменту, когда сервис
                                    // начнёт его читать. Неудача загрузки даёт
                                    // пустой список — подключение не блокируется.
                                    writeBypassFile(context, graph.bypassRoutes.routes())
                                    viewModel.connect()
                                }
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

                        StatusAction.OpenSettings -> openVpnSettings(context)

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
                    // Раскрытие ID — про конкретную сессию: на экране входа
                    // чужого аккаунта маска обязана вернуться сама.
                    account = account.copy(telegramIdRevealed = false)
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
                // Смена страны. Спрашивать ли — решает чистая функция по двум
                // предпочтениям: тумблеру в аккаунте и галочке «больше не
                // спрашивать». Без подтверждения переключаемся сразу — лишний тап
                // там, где обрыв соединения и так ожидаем, только раздражает.
                is AppIntent.SwitchCountry -> {
                    val targetName = configs.firstOrNull { it.id == intent.id }?.name
                    if (askBeforeCountrySwitch(confirmCountrySwitch, dontAskCountrySwitch) && targetName != null) {
                        pendingSwitchCountry = targetName
                        pendingSwitchId = intent.id
                        pendingSwitchDontAsk = false
                    } else {
                        performSwitch(intent.id)
                    }
                }

                // Тумблер «Подтверждать смену страны»: сохраняем в несекретное
                // хранилище, чтобы выбор пережил перезапуск. Раньше интент не
                // обрабатывался, и тумблер показывал константу.
                is AppIntent.SetConfirmCountrySwitch -> {
                    confirmCountrySwitch = intent.enabled
                    graph.settings.confirmCountrySwitch = intent.enabled
                }

                // «Показать/Скрыть» Telegram ID в аккаунте. Интент объявлялся и
                // пробрасывался из экрана, но ветки не было — нажатие молча
                // уходило в `else -> Unit`. Раскрытие живёт в состоянии
                // приложения, а не в маппинге: оно про действие пользователя, а
                // не про ответ сервера.
                AppIntent.ToggleTelegramId ->
                    account = account.copy(telegramIdRevealed = !account.telegramIdRevealed)

                // «Техподдержка»: открыть бота в Telegram. Telegram может не
                // стоять — тогда `startActivity` бросит ActivityNotFoundException,
                // и приложение НЕ должно упасть из-за отсутствия мессенджера.
                AppIntent.OpenSupportChat -> openSupportBot(context)

                AppIntent.OpenDownloadPage -> openDownloadPage(context)

                // Отчёт поддержке: собрать текст из неличных данных, положить его
                // в кеш и в буфер обмена, открыть бота. Файл в кеше и буфер — два
                // способа донести отчёт до чата: глубокой ссылкой Telegram нельзя
                // приложить файл, а вставить из буфера можно. Ключевой материал и
                // содержимое `.ovpn` в отчёт не попадают — см. buildDiagnostics.
                AppIntent.SendDiagnostics -> {
                    val runningId = graph.configManager.currentMeta()?.configId
                    val configId = runningId ?: selectedConfigId
                    val configName = configs.firstOrNull { it.id == configId }?.name
                    val report = buildDiagnostics(
                        DiagnosticsInput(
                            versionName = BuildConfig.VERSION_NAME,
                            versionCode = BuildConfig.VERSION_CODE,
                            gitSha = BuildConfig.GIT_SHA,
                            androidRelease = Build.VERSION.RELEASE,
                            deviceModel = Build.MODEL,
                            tunnelState = status.label(),
                            configId = configId,
                            configName = configName,
                            // Хост/порт сервера не читаем: достать его можно только
                            // из `.ovpn`, а лезть в файл с приватным ключом ради
                            // строки отчёта — не та цена. Честное «неизвестно».
                            serverHostPort = null,
                            timestampIso = Instant.now().toString(),
                        ),
                    )
                    // Файл и буфер — вспомогательные: их провал не должен мешать
                    // главному действию — открыть бота.
                    runCatching {
                        File(context.cacheDir, DIAGNOSTICS_FILE_NAME).writeText(report)
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Отчёт поддержке", report))
                    Toast.makeText(
                        context,
                        "Отчёт скопирован — вставьте в чат поддержки",
                        Toast.LENGTH_LONG,
                    ).show()
                    openSupportBot(context)
                }

                else -> Unit
            }
        },
    )

        // Шит подтверждения смены страны. Поверх AppRoot — см. комментарий у Box.
        // `SheetScaffold` внутри уже кладёт затемнение на весь экран.
        pendingSwitchCountry?.let { countryName ->
            SwitchCountrySheet(
                countryName = countryName,
                dontAskAgain = pendingSwitchDontAsk,
                onDontAskAgainChange = { pendingSwitchDontAsk = it },
                onConfirm = {
                    val target = pendingSwitchId
                    // Галочку сохраняем ДО закрытия шита: переключение — работа в
                    // корутине, а состояние шита сбрасывается синхронно.
                    if (pendingSwitchDontAsk) {
                        dontAskCountrySwitch = true
                        graph.settings.dontAskCountrySwitch = true
                    }
                    pendingSwitchCountry = null
                    pendingSwitchId = null
                    pendingSwitchDontAsk = false
                    switchingChecked = false
                    if (target != null) performSwitch(target)
                },
                onCancel = {
                    pendingSwitchCountry = null
                    pendingSwitchId = null
                    pendingSwitchDontAsk = false
                    switchingChecked = false
                },
            )
        }
    }
}
