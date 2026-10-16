package com.impossi8le.vpnapp.tile

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.impossi8le.vpnapp.MainActivity
import com.impossi8le.vpnapp.R
import com.impossi8le.vpnapp.domain.tunnel.TunnelState
import com.impossi8le.vpnapp.domain.tunnel.TunnelStateStore
import com.impossi8le.vpnapp.vpnservice.VpnTunnelService
import java.io.File

/**
 * Плитка «Быстрых настроек»: подключение одним тапом.
 *
 * **Зачем.** Обычный путь — разблокировать телефон, найти приложение, открыть,
 * дождаться экрана, нажать «Подключить». Для средства обхода блокировок это
 * главный сценарий, и он самый длинный из возможных.
 *
 * **Долгий тап ведёт в приложение.** Система сама выбирает, куда вести по
 * долгому нажатию, и по умолчанию открывает НАСТРОЙКИ приложения. `TileService`
 * не имеет `onLongClick` (проверено по `android.jar` API 35) — обработчик
 * объявляется активностью с действием
 * `android.service.quicksettings.action.QS_TILE_PREFERENCES` в манифесте
 * ([TilePreferencesActivity]). Он и передаёт управление [MainActivity].
 *
 * **Главное ограничение — согласие на VPN.** `VpnService.prepare()` без согласия
 * пользователя возвращает намерение, которое может показать только `Activity`.
 * `TileService` — не активность, диалог показать не может. Поэтому поведение
 * ветвится: согласие дано → плитка включает/выключает туннель молча; согласия
 * нет → открываем приложение, где диалог покажет обычный путь.
 *
 * **Плитка НЕ показывает «защищено».** Она говорит только о состоянии туннеля —
 * ровно как главный экран. §6: поднятый интерфейс не доказывает, что трафик идёт
 * через него, и обещать защиту плитке нечем.
 *
 * **Почему здесь нет доменных типов туннеля.** `TileService` живёт в процессе
 * системы и запускается отдельно от UI; тащить сюда `AppGraph` значило бы
 * поднимать сетевые клиенты и хранилища профиля ради двух состояний. Состояние
 * берётся из общего [TunnelStateStore] — того же, что наполняют сервис и
 * контроллер экрана, — а действия делегируются сервису туннеля.
 */
class VpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        when (currentState()) {
            // Туннель поднят или поднимается — опускаем молча: согласие уже
            // дано, диалога не будет. Повторный тап по «подключается» ожидаемо
            // гасит попытку, а не запускает вторую.
            TunnelState.Established,
            TunnelState.Connecting,
            TunnelState.Recovering,
            -> sendAction(VpnTunnelService.ACTION_DISCONNECT)

            TunnelState.Idle, TunnelState.Unknown -> startTunnel()
        }

        // Пересчёт сразу: ждать следующего onStartListening значит показать
        // пользователю устаревшее состояние после его же тапа.
        updateTile()
    }

    /**
     * Поднять туннель из плитки.
     *
     * **Две вещи, без которых тап молча ничего не делает.**
     *
     * 1. **Согласие на VPN.** Без него `VpnService.Builder.establish()` вернёт
     *    `null` (см. `VpnServiceTunBuilder.establish`), то есть туннель просто не
     *    поднимется. Показать системный диалог может только `Activity`, поэтому
     *    в этом случае открываем приложение — честная передача управления лучше
     *    молчащего тапа.
     * 2. **Путь к профилю.** `ACTION_CONNECT` несёт ПУТЬ, а не текст профиля:
     *    содержимое с приватным ключом в extras намерения было бы видно системе.
     *    Путь плитка может собрать сама — он известен и лежит в том же каталоге,
     *    что и у приложения.
     */
    private fun startTunnel() {
        // `VpnService.prepare` возвращает не-null, если согласие ещё НЕ дано.
        val consent = VpnService.prepare(this)
        if (consent != null) {
            openApp()
            return
        }

        val profile = File(filesDir, PROFILE_NAME)
        if (!profile.exists()) {
            // Профиля нет — поднимать нечего. Открываем приложение: там виден
            // экран с причиной, тогда как плитка показала бы «не работает».
            openApp()
            return
        }

        sendAction(
            action = VpnTunnelService.ACTION_CONNECT,
            profilePath = profile.absolutePath,
            bypassPath = bypassPath(),
        )
    }

    /**
     * Путь к файлу обходов — тот же, что использует приложение.
     *
     * Файл пишется сетью ПЕРЕД подключением с экрана. Плитка сети не трогает, но
     * файл уже лежит на диске, и его надо передать: иначе подключение из плитки
     * молча забыло бы обходы, настроенные пользователем, и часть трафика пошла бы
     * иначе, чем он ожидает. Читаем существующий файл, а не пересобираем список.
     */
    private fun bypassPath(): String = File(filesDir, BYPASS_FILE_NAME).absolutePath

    /** Открыть приложение: показать диалог согласия или причину отказа. */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // startActivityAndCollapse сворачивает шторку «Быстрых настроек» — без
        // него пользователь смотрел бы на неё поверх приложения.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        } else {
            // Перегрузка с `Intent` объявлена устаревшей в API 34, но убрать её
            // нельзя: `PendingIntent`-вариант существует только с 34, а
            // `minSdk` здесь 26 — на 26..33 это ЕДИНСТВЕННЫЙ способ открыть
            // активность из плитки. Поэтому подавляем точечно, а не глобально.
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    /**
     * Состояние туннеля, как его видит плитка.
     *
     * Из общего [TunnelStateStore], а не из собственного снимка: его наполняют и
     * сервис, и сторож существования туннеля на стороне экрана. Один предмет на
     * всех — единственный способ не разойтись с кнопкой приложения (issue #16).
     */
    private fun currentState(): TunnelState = TunnelStateStore.state.value

    private fun sendAction(
        action: String,
        profilePath: String? = null,
        bypassPath: String? = null,
    ) {
        val intent = Intent(this, VpnTunnelService::class.java).setAction(action)
        if (profilePath != null) {
            intent.putExtra(VpnTunnelService.EXTRA_PROFILE, profilePath)
        }
        if (bypassPath != null) {
            intent.putExtra(VpnTunnelService.EXTRA_BYPASS_FILE, bypassPath)
        }
        // startForegroundService: сервис обязан поднять уведомление в течение
        // нескольких секунд, и обычный startService на Android 8+ его не запустит.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateTile() {
        val tile: Tile = qsTile ?: return
        val presentation = tilePresentation(currentState())
        tile.state = presentation.state
        tile.label = presentation.label
        tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher)
        // Подпись и описание доступности дублируют состояние СЛОВАМИ. Это не
        // украшение: у плитки всего три визуальных состояния, и «идёт подключение»
        // обязано отличаться от «выключено» текстом, а не только оттенком,
        // которого пользователь не читает (issue #16, пункт 3).
        setDescriptions(tile, presentation.description)
        tile.updateTile()
    }

    /** `subtitle` (API 29) и `stateDescription` (API 30) — где поддерживаются. */
    private fun setDescriptions(tile: Tile, text: CharSequence?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = text
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = text
        }
    }
}

/**
 * Как выглядит и что говорит плитка в этом состоянии.
 *
 * Отдельный тип, а не разметка внутри сервиса: это чистое решение «какое
 * состояние, какая подпись», и оно проверяется тестом, а не глазами на устройстве.
 */
data class TilePresentation(
    val state: Int,
    val label: String,
    val description: String?,
)

/**
 * Состояние туннеля → вид плитки.
 *
 * **Никакого «защищено».** §6: поднятый интерфейс не доказывает, что трафик идёт
 * через него. Плитка говорит ровно про туннель, и подписи это подтверждают
 * словами — «туннель поднят», а не «всё хорошо».
 *
 * **Почему процесс — `STATE_INACTIVE`, а не `STATE_UNAVAILABLE`.** Раньше
 * `Connecting` ставил `STATE_UNAVAILABLE`, и это выглядело как выключенная плитка:
 * недоступное состояние система рисует серым и не даёт нажать — ровно то, на что
 * жаловался пользователь (пункт 3). Но и `STATE_ACTIVE` был бы ложью: туннеля ещё
 * нет. Честный выбор — обычная плитка (`INACTIVE`), которую можно нажать, с
 * подписью, прямо называющей процесс. От «выключено» её отличает ТЕКСТ; именно
 * поэтому подпись и описание здесь обязательны, а не косметика.
 */
fun tilePresentation(state: TunnelState): TilePresentation = when (state) {
    // Туннель поднят. `ESTABLISHED` — это «интерфейс поднят», и не более:
    // ни слова о защите.
    TunnelState.Established -> TilePresentation(
        state = Tile.STATE_ACTIVE,
        label = "VPN: туннель поднят",
        description = "Подключено. Защита туннеля не измеряется",
    )

    // Поднимается. Туннеля ещё нет, но это идёт работа, а не покой.
    TunnelState.Connecting -> TilePresentation(
        state = Tile.STATE_INACTIVE,
        label = "VPN: подключение…",
        description = "Устанавливаем соединение",
    )

    // Связь пропадала и возвращается. Отдельно от «подключение»: пользователь
    // уже видел поднятый туннель, и в эти секунды трафик идёт напрямую.
    TunnelState.Recovering -> TilePresentation(
        state = Tile.STATE_INACTIVE,
        label = "VPN: восстановление…",
        description = "Связь прервалась, подключаемся заново",
    )

    TunnelState.Idle -> TilePresentation(
        state = Tile.STATE_INACTIVE,
        label = "VPN: отключён",
        description = "Туннель опущен",
    )

    // Незнание — не «выключено»: процесс мог только подняться при работающем
    // туннеле. Показать «отключён» значило бы соврать; показываем как есть.
    TunnelState.Unknown -> TilePresentation(
        state = Tile.STATE_INACTIVE,
        label = "VPN: состояние неизвестно",
        description = "Состояние туннеля ещё не получено",
    )
}

/**
 * Имя файла профиля во внутреннем хранилище.
 *
 * Повторяет путь, которым пользуется приложение (`MainActivity`): профиль ровно
 * один, и отдельного «последнего профиля» не требуется. Если путь когда-нибудь
 * разойдётся, плитка честно откроет приложение, а не поднимет чужое соединение.
 */
private const val PROFILE_NAME = "profile.ovpn"

/**
 * Имя файла обходов во внутреннем хранилище.
 *
 * Строка обязана совпадать с `MainActivity.BYPASS_FILE_NAME` дословно: файл один,
 * и разойдись пути — плитка подняла бы туннель без обходов, настроенных
 * пользователем.
 */
private const val BYPASS_FILE_NAME = "bypass-routes.txt"
