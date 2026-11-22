plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}
kotlin { jvmToolchain(21) }
dependencies {
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.junit5.jupiter)
    testRuntimeOnly(libs.junit5.launcher)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.coroutines.test)
}
tasks.test { useJUnitPlatform() }
