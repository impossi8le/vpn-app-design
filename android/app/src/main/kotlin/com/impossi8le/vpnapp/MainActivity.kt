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
import com.impossi8le.vpnapp.domain.protection.ProtectionFailure
import com.impossi8le.vpnapp.domain.protection.ProtectionGate
import com.impossi8le.vpnapp.domain.protection.ProtectionProbe
import com.impossi8le.vpnapp.domain.protection.ProtectionVerdict
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
 * **Что пока заглушка и почему это честно.** Проба защиты неплатформенная: она
 * сообщает `ProbeUnavailable`, а не зелёное. Туннель — `TunnelEnginePending`,
 * который на попытку подключения отвечает отказом с причиной. Экран поэтому
 * покажет «защита не подтверждена», и это правда: движок ещё не выбран
 * (§4.7 архитектуры), а изображать работающий VPN без движка — ровно та ложная
 * уверенность, против которой написан инвариант защиты.
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
    val tunnel = remember { TunnelEnginePending() }
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

/**
 * Проба, которая честно сообщает, что проверить не может.
 *
 * Не возвращает зелёное: подтверждать нечего, пока нет туннеля. Экран покажет
 * «защита не подтверждена», и это правда, а не удобное умолчание.
 */
private object UnavailableProbe : ProtectionProbe {
    override suspend fun verify(): ProtectionVerdict =
        ProtectionVerdict.Failed(ProtectionFailure.ProbeUnavailable)
}
