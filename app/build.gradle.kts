plugins {
    id("com.android.application")
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.tuyen.danduong"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tuyen.danduong"
        minSdk = 21          // Android 5.0 trở lên (nhiều màn ô tô ghi Android 10 nhưng lõi thật cũ hơn)
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "danduong2026"
            keyAlias = "danduong"
            keyPassword = "danduong2026"
            // Ký cả kiểu cũ (v1) để màn hình ô tô / máy Android đời cũ cài được
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    // Bộ kết nối bảo mật mới (TLS 1.3) cho Android cũ
    implementation("org.conscrypt:conscrypt-android:2.5.2")
}
