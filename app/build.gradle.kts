plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.pulsemix.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pulsemix.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.4"

        // youtubedl-android embarque Python + ffmpeg en binaires natifs.
        // On ne livre que l'ABI arm64-v8a (tous les téléphones ~2017+) pour
        // garder un APK raisonnable (~40 Mo au lieu de ~120 Mo).
        // Ajouter "armeabi-v7a" ici si un vieil appareil 32 bits est visé.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Clé de signature partagée, committée dans le dépôt : tous les builds
    // (debug comme release, quelle que soit la machine) ont la même signature,
    // donc un nouvel APK met toujours à jour l'app installée au lieu d'entrer
    // en conflit avec elle. Ne pas publier cette app sur un store avec cette
    // clé (elle est publique par construction).
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/pulsemix.jks")
            storePassword = "pulsemix"
            keyAlias = "pulsemix"
            keyPassword = "pulsemix"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            // Requis par youtubedl-android : ses binaires (python, ffmpeg)
            // sont livrés comme des .so et doivent être extraits sur disque
            // pour pouvoir être exécutés.
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")

    // Import depuis YouTube / SoundCloud / Bandcamp… : yt-dlp embarqué
    // (fork maintenu par le dev de Seal, publié sur Maven Central).
    // ffmpeg est nécessaire pour l'extraction/conversion audio (-x).
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
