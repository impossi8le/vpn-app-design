// App-side управление туннелем: VpnService.prepare(), старт/стоп foreground-сервиса,
// маппинг системного состояния в ConnectionStatus.
// Сам туннель НЕ поднимает — это делает :vpnservice (разделение из §4.5).
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

    namespace = "com.impossi8le.vpnapp.core.tunnel"
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
    implementation(libs.coroutines.core)

    testImplementation(libs.junit5.api)
    // Нужен для @ParameterizedTest / @CsvSource / @EnumSource.
    testImplementation(libs.junit5.params)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
}
