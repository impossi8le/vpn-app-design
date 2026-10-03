// Аккаунт: состояние входа, выход (очистка сессии и профиля).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    // JUnit5 в android-модулях: по умолчанию AGP ищет JUnit4 и на JUnit5
    // находит НОЛЬ тестов, завершаясь успешно. Это ложная зелень.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    namespace = "com.impossi8le.vpnapp.feature.account"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:ui"))
    implementation(libs.coroutines.core)
    implementation(libs.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    testImplementation(project(":test-support"))
    testImplementation(libs.junit5.api)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
}
