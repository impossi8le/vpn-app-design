// Многомодульный проект Android-клиента.
//
// `core:domain` — проект без Android: чистый Kotlin/JVM. Именно поэтому домен
// и ViewModel-логика тестируются на JVM без эмулятора.
// `core:config` — тоже JVM: файловые операции не требуют Android.
// Остальные `core:*` — Android-библиотеки (нужны системные API).

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "vpn-app-android"

// --- Чистый JVM: тестируется без Android ---
include(":core:domain")
include(":core:config")
include(":core:network")
include(":test-support")

// --- Android-библиотеки ---
include(":core:security")
include(":core:tunnel")
include(":core:protection")
include(":core:ui")
include(":feature:auth")
include(":feature:home")
include(":feature:configs")
include(":feature:account")

// --- Точки входа ---
include(":vpnengine")
include(":vpnservice")
include(":app")

// Вендорится как git submodule (GPLv2), подключается в vpnservice.
// Раскомментировать после `git submodule add` — см. docs/architecture/2026-10-03-android-architecture.md §4.7.
// include(":vendor:ics-openvpn")
