plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.debianbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.debianbox"
        minSdk = 26
        // ВАЖНО: targetSdk 28, иначе Android 10+ запрещает запуск
        // исполняемых файлов из каталога данных приложения (rootfs Debian).
        // Так же делает Termux. В Google Play с таким targetSdk выложить нельзя.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    packaging {
        // Нативные библиотеки (proot) должны быть распакованы на диск
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("org.tukaani:xz:1.9")
}
