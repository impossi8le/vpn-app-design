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
    implementation(libs.coroutines.core)
    // implementation(project(":vendor:ics-openvpn"))

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
}
