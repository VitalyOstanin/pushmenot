plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.vitalyostanin.pushmenot"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.vitalyostanin.pushmenot"
        minSdk = 34
        targetSdk = 36
        versionCode = 10
        versionName = "0.2.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    ksp("androidx.room:room-compiler:2.8.5")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

ksp {
    arg("room.schemaLocation", file("schemas").absolutePath)
}
