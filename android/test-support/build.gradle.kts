// Фейки и фикстуры. Публикуется как обычная библиотека, но подключается
// ТОЛЬКО тест-таргетами (testImplementation) — в продукт не попадает.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.coroutines.core)

    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
