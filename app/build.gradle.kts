plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.paul.sleeptrack"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.paul.sleeptrack"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "2.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    // FileProvider (partage de l'image) et coroutines (rappels en arrière-plan) :
    // déjà tirés en transitif, déclarés ici parce qu'on les utilise directement.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Gemini Nano en local, via AICore. Beta : les signatures peuvent encore bouger.
    // Le modèle est téléchargé par le service système, pas par l'app : aucune
    // permission INTERNET à ajouter ici.
    implementation("com.google.mlkit:genai-prompt:1.0.0-beta4")
}
