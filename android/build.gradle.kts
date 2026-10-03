// Корневой build-файл. Плагины объявлены без применения — применяются в модулях.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// Общие координаты для всех модулей.
allprojects {
    group = "com.impossi8le.vpnapp"
    version = "0.1.0"
}

// JUnit5 для ВСЕХ модулей, включая Android-библиотеки и :app.
//
// По умолчанию Android-плагин гоняет unit-тесты через JUnit4-runner. Если на
// classpath лежат только junit5-api и junit5-engine (JUnit4-движка нет), runner
// не находит НИ ОДНОГО теста и задача завершается УСПЕШНО. Зелёная сборка при
// полностью невыполненных тестах — худший из возможных отказов: инвариант защиты
// и протокол записи считались бы проверенными, не будучи запущенными ни разу.
//
// В JVM-модулях useJUnitPlatform() уже стоит в их tasks.test — повторная
// настройка здесь безвредна.
subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
