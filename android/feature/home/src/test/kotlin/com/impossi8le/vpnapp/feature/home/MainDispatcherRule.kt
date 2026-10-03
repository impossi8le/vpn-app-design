package com.impossi8le.vpnapp.feature.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Подменяет главный диспетчер.
 *
 * `viewModelScope` в ViewModel привязан к `Dispatchers.Main`, которого в обычном
 * JVM-тесте нет вовсе — без подмены тест падает не на логике, а на отсутствии
 * Android-лупера. `UnconfinedTestDispatcher` выполняет корутины сразу, поэтому
 * установка `init`-коллектора успевает произойти до вызова connect().
 */
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : BeforeEachCallback, AfterEachCallback {

    override fun beforeEach(context: ExtensionContext) {
        Dispatchers.setMain(dispatcher)
    }

    override fun afterEach(context: ExtensionContext) {
        Dispatchers.resetMain()
    }
}
