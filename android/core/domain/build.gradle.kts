// Чистый Kotlin/JVM. Ноль Android-зависимостей — принцип §1 архитектуры.
// Благодаря этому весь домен тестируется на JVM без эмулятора и Robolectric.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Модель события телеметрии сериализуется в JSON контрактом (§ Task 4).
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Только корутины. Ни OkHttp, ни Android, ни Compose.
    implementation(libs.coroutines.core)
    // Модель события телеметрии аннотирована @Serializable — плагин требует
    // рантайм сериализации на входе компиляции.
    implementation(libs.serialization.json)

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
