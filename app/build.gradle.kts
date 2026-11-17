plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nova.tv"
    compileSdk = 34
    defaultConfig {
        // New application id = installs as a separate app, never replaces the old "Shuja TV". (namespace stays com.nova.tv so no code moves)
        applicationId = "com.shuja.tvv2"
        minSdk = 21
        targetSdk = 34
        // versionCode grows with every build (minutes since 1970), so each new APK installs over the previous one.
        versionCode = (System.currentTimeMillis() / 60000L).toInt()
        versionName = "2.0"
    }
    signingConfigs {
        // Fixed key kept in the repo: every build is signed the same way, so updates install over the old app.
        create("shuja") {
            storeFile = file("shuja-release.jks")
            storeType = "pkcs12"
            storePassword = "shujatv123"
            keyAlias = "shuja"
            keyPassword = "shujatv123"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shuja")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource:1.4.1")
    implementation("androidx.media3:media3-database:1.4.1")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
