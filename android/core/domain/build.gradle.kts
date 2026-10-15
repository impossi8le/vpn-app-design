// Чистый Kotlin/JVM. Ноль Android-зависимостей — принцип §1 архитектуры.
// Благодаря этому весь домен тестируется на JVM без эмулятора и Robolectric.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Модель телеметрии сериализуется в JSON — без плагина `@Serializable`
    // не генерирует код, и `encodeToString` не компилируется.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Только корутины и сериализация. Ни OkHttp, ни Android, ни Compose.
    implementation(libs.coroutines.core)
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
