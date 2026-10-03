package com.impossi8le.vpnapp.feature.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Подменяет главный диспетчер и ОТДАЁТ свой планировщик тесту.
 *
 * `viewModelScope` привязан к `Dispatchers.Main`, которого в обычном JVM-тесте
 * нет — без подмены тест падает не на логике, а на отсутствии Android-лупера.
 *
 * Планировщик обязательно общий с `runTest`: если у диспетчера и у теста разные
 * планировщики, корутина-коллектор внутри ViewModel никогда не возобновляется —
 * состояние «застревает» на первом значении, и тест начинает проверять не то,
 * что задумано. Поэтому тест обязан писать `runTest(rule.scheduler)`.
 */
class MainDispatcherRule : BeforeEachCallback, AfterEachCallback {

    val scheduler = TestCoroutineScheduler()
    val dispatcher = UnconfinedTestDispatcher(scheduler)

    override fun beforeEach(context: ExtensionContext) {
        Dispatchers.setMain(dispatcher)
    }

    override fun afterEach(context: ExtensionContext) {
        Dispatchers.resetMain()
    }
}
