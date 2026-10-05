package com.impossi8le.vpnapp.testsupport

import com.impossi8le.vpnapp.domain.settings.AppSettingsStore

/**
 * Настройки в памяти.
 *
 * Дефолты повторяют боевую реализацию ([com.impossi8le.vpnapp.SharedPrefsSettingsStore]):
 * подтверждать смену страны — да, «больше не спрашивать» — нет. Иначе тест
 * проверял бы не то поведение, которое увидит пользователь.
 */
class FakeAppSettingsStore(
    confirmCountrySwitch: Boolean = true,
    dontAskCountrySwitch: Boolean = false,
) : AppSettingsStore {

    override var confirmCountrySwitch: Boolean = confirmCountrySwitch
    override var dontAskCountrySwitch: Boolean = dontAskCountrySwitch
}
