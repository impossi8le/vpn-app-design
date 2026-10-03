// Чистый Kotlin/JVM. Ноль Android-зависимостей — принцип §1 архитектуры.
// Благодаря этому весь домен тестируется на JVM без эмулятора и Robolectric.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Только корутины. Ни OkHttp, ни Android, ни Compose.
    implementation(libs.coroutines.core)

    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
