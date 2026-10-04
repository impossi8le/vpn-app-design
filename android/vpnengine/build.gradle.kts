// Модуль движка: нативная библиотека OpenVPN 3 и её Java-биндинг.
//
// Устроен необычно, и это осознанно. Движок **не собирается здесь**: его
// собирает отдельная джоба CI (`engine` в android.yml) по рецепту из
// CMakeLists.txt этого же каталога, потому что сборка тянет NDK, CMake и vcpkg
// с шестью библиотеками из исходников — это ~10 минут, и держать это внутри
// сборки каждого APK значит сделать каждую итерацию на десять минут медленнее.
//
// Здесь лежит то, что уже собрано: нативные библиотеки и Java-классы биндинга,
// скачанные из артефактов CI. Модуль их только упаковывает.
//
// Если артефактов нет — сборка падает с внятным сообщением, а не собирает APK
// без движка: APK без движка выглядел бы рабочим, но подключаться не смог бы,
// и это ровно та ложная уверенность, против которой написан §6.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
    }

    namespace = "com.impossi8le.vpnapp.vpnengine"
    compileSdk = 35
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Нативные библиотеки и Java-биндинг разложены по каталогам, которые Gradle
    // считает стандартными, поэтому дополнительных настроек sourceSets не нужно.
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
            java.srcDirs("src/main/java")
        }
    }

    // Ничего не минифицируем и не вырезаем: биндинг SWIG опирается на имена
    // методов в JNI (`ovpncliJNI`), и обфускация их ломает.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Зависимость на домен — ради ДВУХ чистых типов: CoreConfig (уровень
    // логирования) и ProfileSanitizer (правка verb в тексте профиля).
    // Оба обязаны применяться до передачи профиля ядру, иначе `verb 3` из
    // боевого профиля печатает тела PEM, то есть приватный ключ, в logcat.
    //
    // Обратной зависимости нет: `core:domain` про этот модуль не знает. А
    // `net.openvpn.ovpn3` наружу не торчит — типы ядра остаются внутри.
    implementation(project(":core:domain"))

    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
}

// Проверка предусловия: без собранного движка модуль бессмыслен, и лучше узнать
// об этом сразу, чем получить APK, который падает на System.loadLibrary.
val engineLib = file("src/main/jniLibs")
tasks.matching { it.name == "preBuild" }.configureEach {
    doFirst {
        val abis = engineLib.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
        if (abis.isEmpty()) {
            throw GradleException(
                "Движок не разложен: нет каталогов в ${engineLib.path}. " +
                    "Возьмите артефакты ovpn3-engine-arm64-v8a и ovpn3-engine-x86_64 " +
                    "из джобы 'engine' в CI и распакуйте в src/main/jniLibs/<abi>/ и src/main/java/.",
            )
        }
    }
}
