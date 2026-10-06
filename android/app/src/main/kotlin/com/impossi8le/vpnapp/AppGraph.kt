package com.impossi8le.vpnapp

import android.content.Context
import com.impossi8le.vpnapp.config.ConfigManager
import com.impossi8le.vpnapp.config.FileProfileMetaStore
import com.impossi8le.vpnapp.config.FileProfileStore
import com.impossi8le.vpnapp.config.ProfilePreparer
import com.impossi8le.vpnapp.domain.auth.SessionStore
import com.impossi8le.vpnapp.domain.config.ProfileMetaStore
import com.impossi8le.vpnapp.domain.config.ProfileStore
import com.impossi8le.vpnapp.domain.settings.AppSettingsStore
import com.impossi8le.vpnapp.domain.tunnel.TunnelControlling
import com.impossi8le.vpnapp.domain.update.FallbackUpdateService
import com.impossi8le.vpnapp.domain.update.UpdateService
import com.impossi8le.vpnapp.network.ApiClient
import com.impossi8le.vpnapp.network.AuthApi
import com.impossi8le.vpnapp.network.ConfigApi
import com.impossi8le.vpnapp.network.ServerUpdateApi
import com.impossi8le.vpnapp.network.UpdateApi
import com.impossi8le.vpnapp.security.AndroidSecureBackend
import com.impossi8le.vpnapp.security.SessionStoreImpl
import java.io.File

/**
 * Точка сборки: сеть, хранилища и координаторы в одном месте.
 *
 * DI-библиотеку не тянем осознанно: модулей с состоянием мало, а граф в двадцать
 * строк виден целиком. Библиотека принесла бы генерацию и правила, которых нечем
 * оправдать.
 *
 * **Границы §6.** Здесь нет и не может быть `ProtectionVerdict`: зелёный статус
 * собирается единственным конструктором в `core:domain` из трёх измеренных
 * фактов. Ни один метод этого класса не способен его породить.
 */
class AppGraph(
    context: Context,
    private val tunnel: TunnelControlling,
) {
    private val filesDir: File = context.applicationContext.filesDir

    val apiClient = ApiClient(baseUrl = API_BASE_URL)
    val authApi = AuthApi(apiClient)
    val configApi = ConfigApi(apiClient)

    /**
     * Источник сведений о новой версии: наш сервер, а GitHub — запасной.
     *
     * Сервер первый, потому что `api.github.com` в РФ бывает недоступен, а
     * обновление не должно зависеть от чужого домена. GitHub оставлен до тех
     * пор, пока серверные эндпоинты не проверены на устройстве (`docs/api/`).
     */
    val updateApi: UpdateService = FallbackUpdateService(
        primary = ServerUpdateApi(apiClient),
        fallback = UpdateApi(apiClient),
    )

    val sessionStore: SessionStore = SessionStoreImpl(AndroidSecureBackend(context))

    /**
     * Несекретные настройки интерфейса: «подтверждать смену страны» и «больше не
     * спрашивать». Обычные `SharedPreferences`, а НЕ зашифрованное хранилище —
     * это предпочтения, а не секреты; шифрование остаётся границей сессии и
     * приватного ключа (§6/§7), и размывать её незачем.
     */
    val settings: AppSettingsStore = SharedPrefsSettingsStore(context)
    val profileStore: ProfileStore = FileProfileStore(filesDir)
    val metaStore: ProfileMetaStore = FileProfileMetaStore(filesDir)

    val configManager = ConfigManager(configApi, profileStore, metaStore)
    val preparer = ProfilePreparer(configApi, configManager, profileStore, metaStore)

    /**
     * Восстановить сессию при запуске. `true` — токен жив, вход не нужен.
     *
     * Истёкшую сессию чистим сразу: держать протухший токен — значит получить
     * `401` на первом же запросе и разбираться с ним на экране вместо входа.
     */
    fun restoreSession(): Boolean {
        val session = sessionStore.load() ?: return false
        if (session.expiresAtEpochSeconds <= System.currentTimeMillis() / 1000) {
            sessionStore.clear()
            return false
        }
        apiClient.sessionToken = session.token
        return true
    }

    /**
     * Полный выход. Порядок обязателен и повторяет `AccountViewModel`:
     * туннель → профиль с метаданными → сессия → токен в памяти.
     *
     * Ядро не должно держать удаляемый профиль; сессия не должна исчезнуть
     * раньше поднятого соединения. Профиль уходит вместе с сессией — иначе
     * следующая сессия подхватила бы чужой профиль.
     */
    suspend fun signOut() {
        tunnel.disconnect()
        profileStore.clear()
        metaStore.clear()
        sessionStore.clear()
        apiClient.sessionToken = null
    }
}
