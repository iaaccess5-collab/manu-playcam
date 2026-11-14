plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.manumusicprod.playcam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.manumusicprod.playcam"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    // Clé de signature de TEST incluse dans le projet : l'APK s'installe directement et les mises à jour
    // gardent la même signature. Pour publier sur le Play Store, remplace-la par ta propre clé (voir README).
    signingConfigs {
        create("mmpTest") {
            storeFile = file("mmp-test.keystore")
            storePassword = "manuplaycam"
            keyAlias = "manuplaycam"
            keyPassword = "manuplaycam"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("mmpTest")
        }
        debug {
            signingConfig = signingConfigs.getByName("mmpTest")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    val camerax = "1.4.0"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-effects:$camerax")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}
