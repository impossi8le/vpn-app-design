// VpnService, foreground-сервис, JNI-мост к ics-openvpn.
//
// ЗАВИСИМОСТЬ ОТ vendor:ics-openvpn (GPLv2) раскомментируется после
// `git submodule add` — см. docs/architecture/2026-10-03-android-architecture.md §4.7.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    // JUnit5 в android-модулях: по умолчанию AGP ищет JUnit4 и на JUnit5
    // находит НОЛЬ тестов, завершаясь успешно. Это ложная зелень.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    buildFeatures {
        // buildConfig нужен ради BuildConfig.DEBUG: логи ядра включаем
        // только в отладочной сборке, в релизе им в logcat не место.
        buildConfig = true
    }

    namespace = "com.impossi8le.vpnapp.vpnservice"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:config"))
    // Ядро OpenVPN 3: нативная библиотека и Java-биндинг. Модуль только
    // упаковывает то, что собрала джоба `engine` в CI (§4.7).
    implementation(project(":vpnengine"))
    implementation(libs.coroutines.core)

    testImplementation(libs.junit5.api)
    // Нужен для @ParameterizedTest / @CsvSource / @EnumSource.
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
}
