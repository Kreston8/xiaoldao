import java.io.File

plugins {
    id("com.android.application")
}

// CI passes -PversionCode / -PversionName so every release build is unique.
val appVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName = (project.findProperty("versionName") as String?) ?: "2.14.0"

// The release certificate is pinned: the same RSA-2048 key signs every build, so a newer APK
// installs straight over the previous one instead of demanding an uninstall. CI restores the
// keystore from repository secrets; when it is absent (local build, fork, or a deleted secret) the
// build falls back to the debug key and then does need a clean install.
val pinnedKeystore = System.getenv("ANDROID_KEYSTORE_PATH")?.let { File(it) }
val hasPinnedKey = pinnedKeystore != null && pinnedKeystore.isFile

android {
    namespace = "com.kreston8.islandcn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kreston8.islandcn"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasPinnedKey) {
            create("pinned") {
                storeFile = pinnedKeystore
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                // PKCS12 keeps one password for the store and the key, the values are the same.
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (hasPinnedKey) {
                signingConfigs.getByName("pinned")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
