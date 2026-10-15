package com.impossi8le.vpnapp.tile

import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.impossi8le.vpnapp.MainActivity
import com.impossi8le.vpnapp.R
import com.impossi8le.vpnapp.vpnservice.TunnelStateSnapshot
import com.impossi8le.vpnapp.vpnservice.VpnTunnelService
import java.io.File

/**
 * Плитка «Быстрых настроек»: подключение одним тапом.
 *
 * **Зачем.** Обычный путь — разблокировать телефон, найти приложение, открыть,
 * дождаться экрана, нажать «Подключить». Для средства обхода блокировок это
 * главный сценарий, и он самый длинный из возможных.
 *
 * **Главное ограничение — согласие на VPN.** `VpnService.prepare()` без согласия
 * пользователя возвращает намерение, которое может показать только `Activity`.
 * `TileService` — не активность, диалог показать не может. Поэтому поведение
 * ветвится:
 *  - согласие дано → плитка включает/выключает туннель молча;
 *  - согласия нет → открываем приложение, где диалог покажет обычный путь.
 *
 * Обойти это нельзя: без согласия система не поднимет туннель, и любая попытка
 * выглядела бы как «плитка не работает». Честнее открыть приложение и сказать
 * словами, чем молча ничего не сделать.
 *
 * **Плитка НЕ показывает «защищено».** Она говорит только о состоянии туннеля —
 * ровно как главный экран. §6: поднятый интерфейс не доказывает, что трафик идёт
 * через него, и обещать защиту плитке нечем.
 *
 * **Почему здесь нет доменных типов.** `TileService` живёт в процессе системы и
 * запускается отдельно от UI; тащить сюда `AppGraph` значило бы поднимать
 * сетевые клиенты и хранилища профиля ради двух состояний. Плитка читает статус
 * у сервиса туннеля и делегирует действия ему же.
 */
class VpnTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        when (TunnelTileState.from(readTunnelState())) {
            // Туннель поднят или поднимается — опускаем молча: согласие уже
            // дано, диалога не будет. Повторный тап по «подключается» ожидаемо
            // гасит попытку, а не запускает вторую.
            TunnelTileState.Connected, TunnelTileState.Connecting ->
                sendAction(VpnTunnelService.ACTION_DISCONNECT)

            TunnelTileState.Disconnected, TunnelTileState.Unknown -> startTunnel()
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
            VpnTunnelService.ACTION_CONNECT,
            profile.absolutePath,
        )
    }

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
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    /**
     * Состояние туннеля, как его видит плитка.
     *
     * Читается из статического снимка, а не из репозитория: `TileService`
     * запускается системой и до создания UI, когда графа приложения ещё нет.
     */
    private fun readTunnelState(): TunnelStateSnapshot.State =
        TunnelStateSnapshot.read()

    private fun sendAction(action: String, profilePath: String? = null) {
        val intent = Intent(this, VpnTunnelService::class.java).setAction(action)
        if (profilePath != null) {
            intent.putExtra(VpnTunnelService.EXTRA_PROFILE, profilePath)
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
        when (TunnelTileState.from(readTunnelState())) {
            TunnelTileState.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "VPN подключён"
            }

            TunnelTileState.Connecting -> {
                // UNAVAILABLE, а не ACTIVE: показать «включено», пока туннель
                // ещё поднимается, значило бы обещать связь, которой нет.
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "VPN подключается…"
            }

            TunnelTileState.Disconnected, TunnelTileState.Unknown -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "VPN отключён"
            }
        }
        tile.icon = Icon.createWithResource(this, R.mipmap.ic_launcher)
        tile.updateTile()
    }
}

/**
 * Имя файла профиля во внутреннем хранилище.
 *
 * Повторяет путь, которым пользуется приложение (`MainActivity`): профиль ровно
 * один, и отдельного «последнего профиля» не требуется. Если путь когда-нибудь
 * разойдётся, плитка честно откроет приложение, а не поднимет чужое соединение.
 */
private const val PROFILE_NAME = "profile.ovpn"

/** Состояние туннеля в терминах плитки. */
enum class TunnelTileState {
    Unknown, Connected, Connecting, Disconnected;

    companion object {
        /** Снимок сервиса → состояние плитки. Разбор живёт в `:vpnservice`. */
        fun from(snapshot: TunnelStateSnapshot.State): TunnelTileState = when (snapshot) {
            TunnelStateSnapshot.State.Connected -> Connected
            TunnelStateSnapshot.State.Connecting -> Connecting
            TunnelStateSnapshot.State.Disconnected -> Disconnected
            TunnelStateSnapshot.State.Unknown -> Unknown
        }
    }
}
