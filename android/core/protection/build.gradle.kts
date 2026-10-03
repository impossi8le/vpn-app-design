// Проба защиты на Android-примитивах (ConnectivityManager, LinkProperties).
//
// ЧЕСТНАЯ ОГОВОРКА (§4.6): этот модуль компилируется только под Android.
// Вычисление вердикта живёт в core:domain и тестируется на JVM;
// здесь — только сбор фактов, который проверяется инструментально на эмуляторе.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.impossi8le.vpnapp.core.protection"
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
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit5.engine)
}
