// Android-библиотека: EncryptedSharedPreferences и Keystore существуют только на Android.
// Логика хранения тестируется против fake-SecureBackend — устройство не нужно.
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

    namespace = "com.impossi8le.vpnapp.core.security"
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
    implementation(libs.security.crypto)

    testImplementation(libs.junit5.api)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
}
