// Чистый Kotlin/JVM: файловые операции (temp+rename) не требуют Android.
// Это позволяет тестировать протокол согласованной записи (§7) без Robolectric.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    testImplementation(libs.junit5.api)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.junit5.engine)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
