plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.misura.phone"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.misura.phone"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "DIAGNOSTICS", "false")
    }
    // ponytail: -Pfast = same debug key and data, but not debuggable, to measure real frame times on the phone.
    buildTypes {
        getByName("debug") {
            isDebuggable = !project.hasProperty("fast")
            buildConfigField("boolean", "DIAGNOSTICS", "true")
        }
    }
    buildFeatures { compose = true; aidl = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true; unitTests.all { it.environment("MISURA_TOKEN", System.getenv("MISURA_TOKEN") ?: "") } }
}
kotlin { jvmToolchain(21) }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.maplibre)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    ksp(libs.room.compiler)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotest.assertions)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.espresso.core) // 3.5 from compose ui-test crashes on Android 17 (InputManager.getInstance)
    debugImplementation(libs.compose.ui.test.manifest)
}
