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
        versionCode = 13
        versionName = "3.1.1-beta"
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
    // Previews d'Android Studio (Accueil, Grilles, Réglages, jauge, pastilles) : debug seulement.
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.health.connect:connect-client:1.1.0")
    // FileProvider (partage de l'image) et coroutines (rappels en arrière-plan) :
    // déjà tirés en transitif, déclarés ici parce qu'on les utilise directement.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Le calcul du score de récupération, testé sur la JVM sans appareil.
    testImplementation("junit:junit:4.13.2")
}
