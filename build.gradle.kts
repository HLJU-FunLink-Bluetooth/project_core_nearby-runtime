plugins {
    id("com.android.library")
}

android {
    namespace = "com.hlju.funlinkbluetooth.core.nearby.runtime"
    compileSdk = 37

    defaultConfig {
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.google.play.services.nearby)
    implementation(project(":core:model"))
    implementation(project(":core:plugin-api"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.bundles.unit.test.base)
    testImplementation(libs.test.mockk)
}
