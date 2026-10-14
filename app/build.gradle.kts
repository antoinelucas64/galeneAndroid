plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * One APK per processor family: WebRTC's native library is most of the app's size and
 * a phone only needs one of them. Each APK gets `versionCode * 10 + code` (the universal
 * APK `* 10 + 0`), the scheme F-Droid's `VercodeOperation` expects.
 */
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)
val appVersionCode = 3

android {
    namespace = "fr.antoineassocies.galene"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.antoineassocies.galene"
        minSdk = 26  // Android 8.0 — covers 95%+ of active devices
        targetSdk = 36
        versionCode = appVersionCode
        versionName = "0.2.1"

        // Languages of the app (res/values-*); the libraries' other translations are dropped
        resourceConfigurations += listOf(
            "en", "fr", "de", "es", "it", "pt", "pt-rBR", "nl", "pl", "ru", "uk",
            "sv", "tr", "zh-rCN", "zh-rTW", "ja", "ko", "hi", "ar",
        )
    }

    // Lists those languages in the system's per-app language setting (Android 13+)
    androidResources {
        generateLocaleConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*abiCodes.keys.toTypedArray())
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Compressed native libraries: half the download size, extracted at install time
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Google-encrypted dependency list: unreadable by anyone else, refused by F-Droid
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
            output.versionCode.set(appVersionCode * 10 + (abiCodes[abi] ?: 0))
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.webrtc.android)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
