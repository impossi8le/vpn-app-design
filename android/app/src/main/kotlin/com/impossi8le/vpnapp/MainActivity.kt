package com.impossi8le.vpnapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.impossi8le.vpnapp.core.ui.VpnColors
import com.impossi8le.vpnapp.domain.protection.ProtectionGate
import com.impossi8le.vpnapp.feature.home.HomeScreen
import com.impossi8le.vpnapp.feature.home.HomeViewModel
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
 * **Что сейчас в сборке.** Туннель — `DemoTunnelEngine`: движок уже собран в
 * `:vpnengine`, но к сервису ещё не подключён, поэтому попытка подключения
 * отвечает причиной, а не имитацией. Проба защиты неплатформенная и сообщает
 * `ProbeUnavailable`. Экран поэтому покажет «защита не подтверждена», и это
 * правда: изображать работающий VPN без поднятого туннеля — ровно та ложная
 * уверенность, против которой написан инвариант §6.
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
                HomeRoute()
            }
        }
    }
}

@Composable
private fun HomeRoute() {
    val tunnel = remember { DemoTunnelEngine() }
    val viewModel: HomeViewModel = viewModel {
        HomeViewModel(tunnel, ProtectionGate(UnavailableProbe))
    }

    val status by viewModel.status.collectAsState()
    val scope = rememberCoroutineScope()

    // Наблюдение привязано к жизненному циклу экрана: таймер перепроверки делает
    // сетевые обращения, и держать его вне экрана незачем.
    DisposableEffect(Unit) {
        viewModel.start()
        onDispose { viewModel.stop() }
    }

    HomeScreen(
        status = status,
        onConnect = { scope.launch { viewModel.connect() } },
        onDisconnect = { scope.launch { viewModel.disconnect() } },
    )
}
