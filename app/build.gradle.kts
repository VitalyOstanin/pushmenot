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
        minSdk = 31
        targetSdk = 36
        versionCode = 12
        versionName = "0.2.10"
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

configurations.matching { it.name.startsWith("ksp") && it.name.endsWith("ProcessorClasspath") }
    .configureEach {
        resolutionStrategy.force("com.google.guava:guava:33.7.2-jre")
    }

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.sqlite:sqlite-bundled:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    ksp("androidx.room:room-compiler:2.8.5")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

ksp {
    arg("room.schemaLocation", file("schemas").absolutePath)
}

// Both variants export the same Room schema files.
tasks.matching { it.name == "kspReleaseKotlin" }.configureEach {
    mustRunAfter("kspDebugKotlin")
}
