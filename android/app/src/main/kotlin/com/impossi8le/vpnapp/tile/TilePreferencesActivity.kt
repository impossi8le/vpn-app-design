package com.impossi8le.vpnapp.tile

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Куда ведёт ДОЛГОЕ нажатие по плитке «Быстрых настроек».
 *
 * **Зачем отдельная активность.** У `TileService` нет обработчика долгого тапа
 * (в `android.jar` API 35 такого метода нет — есть только `onClick`). Система
 * сама решает, что открыть, и по умолчанию открывает НАСТРОЙКИ приложения. Чтобы
 * повести в приложение, нужно объявить в манифесте активность с действием
 * `android.service.quicksettings.action.QS_TILE_PREFERENCES`: система считает её
 * экраном «настроек плитки» и открывает именно её.
 *
 * **Почему это переадресация, а не экран.** У плитки нет собственных настроек —
 * всё, что пользователю нужно, лежит на главном экране. Поэтому активность не
 * рисует ничего (`NoDisplay` в манифесте) и сразу передаёт управление
 * [com.impossi8le.vpnapp.MainActivity]. Обойтись одним фильтром на `MainActivity`
 * нельзя: тогда долгий тап кликапливал бы её с намерением QS_TILE_PREFERENCES, а
 * её точкой входа остаётся LAUNCHER — смешивать их не стоит.
 */
class TilePreferencesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, com.impossi8le.vpnapp.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        // Экран-переадресация не должен остаться в стеке: возврат «Назад» из
        // приложения обязан вести к рабочему столу, а не в пустую активность.
        finish()
    }
}
