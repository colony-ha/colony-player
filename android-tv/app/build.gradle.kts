plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStorePassword = System.getenv("COLONY_UPLOAD_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("COLONY_UPLOAD_KEY_ALIAS")
val releaseKeyPassword = System.getenv("COLONY_UPLOAD_KEY_PASSWORD")
val hasReleaseSigning = !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "com.colony.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.colony.player"
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "2.2.0"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file("upload-keystore.jks")
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
