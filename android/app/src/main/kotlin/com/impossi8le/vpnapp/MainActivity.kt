package com.impossi8le.vpnapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier

/**
 * Точка входа. Заглушка каркаса.
 *
 * Здесь будет composition root: сборка модулей через DI и стартовая навигация.
 * Логики экранов этот класс не содержит — она в feature-модулях (§4.11 архитектуры).
 *
 * `launchMode="singleTask"` в манифесте нужен для deep link входа:
 * возврат из Telegram не должен создавать второй экземпляр активности.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                Text("Каркас. Реализация — потоки WA1…WA5.")
            }
        }
    }
}
