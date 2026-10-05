// Composition root: DI, навигация, точка входа.
// Логики не содержит — только сборка модулей (§4.11).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    // JUnit5 в android-модулях: по умолчанию AGP ищет JUnit4 и на JUnit5
    // находит НОЛЬ тестов, завершаясь успешно. Это ложная зелень.
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    namespace = "com.impossi8le.vpnapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.impossi8le.vpnapp"
        minSdk = 26
        targetSdk = 35
        // versionCode инкрементируется в CI, иначе обновление APK не установится
        // поверх предыдущего (§10.3). Значение подставляется из CI.
        versionCode = (System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull()) ?: 1
        versionName = System.getenv("ANDROID_VERSION_NAME") ?: "0.1.0"

        // Короткий SHA коммита: по нему сборка из релиза опознаётся однозначно.
        // Локально (нет GIT_SHA) — "dev", это честно: сборка не из git.
        buildConfigField(
            "String",
            "GIT_SHA",
            "\"${System.getenv("GIT_SHA") ?: "dev"}\"",
        )

        // Без этого connectedAndroidTest не запускается вовсе: инструментам
        // нечем стартовать тесты. AndroidJUnitRunner работает на JUnit4 —
        // JUnit5 там не поддерживается, поэтому у инструментальных тестов
        // собственный контур (см. TunnelPlanInstrumentedTest).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        // buildConfig нужен ради BuildConfig.DEBUG: демонстрационный проход
        // показываем ТОЛЬКО в debug-сборке, в release кнопки быть не должно.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Подпись release-сборки приходит из GitHub Secrets (§10.3): keystore, alias и
    // пароли — переменными окружения. Локально release не подписывается: это осознанно,
    // чтобы нельзя было случайно выпустить сборку чужим ключом.
    //
    // Потеря keystore = невозможность обновить установленное приложение.
    signingConfigs {
        System.getenv("ANDROID_KEYSTORE_PATH")?.let { keystorePath ->
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // При REQUIRE_SIGNING=true релиз без настроенной подписи должен падать, а не
    // молча выпускать app-release-unsigned.apk: иначе артефакт релиза окажется
    // неподписанным и не установится ни у кого (§10.3).
    if (System.getenv("REQUIRE_SIGNING") == "true") {
        tasks.matching { it.name == "assembleRelease" }.configureEach {
            doFirst {
                check(signingConfigs.findByName("release") != null) {
                    "REQUIRE_SIGNING=true, но подпись release не настроена: " +
                        "нет ANDROID_KEYSTORE_PATH и паролей. Прерываю до сборки."
                }
            }
        }
    }
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:network"))
    implementation(project(":core:config"))
    implementation(project(":core:security"))
    implementation(project(":core:tunnel"))
    implementation(project(":core:protection"))
    implementation(project(":core:ui"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:home"))
    implementation(project(":feature:configs"))
    implementation(project(":feature:account"))
    implementation(project(":vpnservice"))
    implementation(project(":vpnengine"))

    // ApiClient/AuthApi/ConfigApi expose OkHttpClient и Json в ПУБЛИЧНЫХ
    // сигнатурах (значения по умолчанию в конструкторах), а core:network держит
    // их как `implementation`. Без этих строк `AppGraph` не скомпилируется:
    // компилятору нужны типы из сигнатур, а не только используемые внутри.
    implementation(libs.okhttp)
    implementation(libs.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.coroutines.core)

    testImplementation(libs.junit5.api)
    // Нужен для @ParameterizedTest / @CsvSource / @EnumSource.
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
    // Скачивание APK проверяется против подставного сервера, а не сети.
    testImplementation(libs.okhttp)
    testImplementation(libs.okhttp.mockwebserver)
    // runTest: тесты обновления асинхронные, как и их код.
    testImplementation(libs.coroutines.test)

    // Инструментальные тесты идут под JUnit4: AndroidJUnitRunner не умеет JUnit5,
    // поэтому это отдельный контур и отдельная задача (connectedAndroidTest),
    // а не продолжение юнит-тестов.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
