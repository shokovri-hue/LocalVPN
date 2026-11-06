import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Ключ подписи берётся из секретов GitHub (см. README). Без него сборка подписывается
// временным отладочным ключом — такой APK ставится, но не обновляется поверх прошлой версии.
val keystorePath: String? = System.getenv("LOCALVPN_KEYSTORE_FILE")
val hasReleaseKey = keystorePath != null && file(keystorePath).exists()
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "app.localvpn"
    compileSdk = 36 // нужно библиотеке AmneziaWG

    defaultConfig {
        applicationId = "app.localvpn"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("LOCALVPN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LOCALVPN_KEY_ALIAS") ?: "localvpn"
                keyPassword = System.getenv("LOCALVPN_KEY_PASSWORD") ?: System.getenv("LOCALVPN_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // AmneziaWG: WireGuard с маскировкой трафика. Обычные WireGuard-конфиги тоже работают.
    implementation("com.zaneschepke:amneziawg-android:2.3.7")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.webkit:webkit:1.11.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
