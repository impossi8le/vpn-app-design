package com.impossi8le.vpnapp

import android.content.Context
import android.content.SharedPreferences
import com.impossi8le.vpnapp.domain.settings.AppSettingsStore

/**
 * Настройки интерфейса на `SharedPreferences`.
 *
 * Обычные, НЕ зашифрованные: здесь нет секретов — только предпочтения показа
 * («подтверждать смену страны», «больше не спрашивать»). Зашифрованное хранилище
 * в проекте есть, но оно под сессию и приватный ключ; кладя туда предпочтения, мы
 * платили бы расшифровкой на каждое чтение и размывали границу «что защищаем».
 *
 * `SharedPreferences`, а не файл: значение одно-двухбитное, читается на старте,
 * и своя сериализация здесь была бы лишним кодом без выгоды.
 */
class SharedPrefsSettingsStore(
    context: Context,
    prefsName: String = "vpnapp-settings",
) : AppSettingsStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    override var confirmCountrySwitch: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM_COUNTRY_SWITCH, DEFAULT_CONFIRM)
        set(value) {
            prefs.edit().putBoolean(KEY_CONFIRM_COUNTRY_SWITCH, value).apply()
        }

    override var dontAskCountrySwitch: Boolean
        get() = prefs.getBoolean(KEY_DONT_ASK_COUNTRY_SWITCH, false)
        set(value) {
            prefs.edit().putBoolean(KEY_DONT_ASK_COUNTRY_SWITCH, value).apply()
        }

    private companion object {
        const val KEY_CONFIRM_COUNTRY_SWITCH = "confirm_country_switch"
        const val KEY_DONT_ASK_COUNTRY_SWITCH = "dont_ask_country_switch"

        /** Дефолт — «спрашивать»: молчаливый обрыв соединения хуже лишнего тапа. */
        const val DEFAULT_CONFIRM = true
    }
}
