plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nogamt.showroom"

    // Bump these two together with the AGP version in the root build.gradle.kts
    // if you want to target a newer platform. 34 = Android 14.
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nogamt.showroom"
        minSdk = 26          // Android 8.0 - covers essentially every Android TV in the field
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"

        // No test runner wired in: this is a kiosk shell, verification is manual (see README).
        resourceConfigurations += listOf("en")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false      // keep the JS bridge trivially debuggable on site
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // No signingConfig on purpose: `assembleRelease` produces an UNSIGNED apk
            // unless you add a keystore. See README -> "Signing a release build".
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.annotation:annotation:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Modern WebView features (document-start scripts, safe-browsing, version info)
    implementation("androidx.webkit:webkit:1.11.0")

    // Storage Access Framework helpers
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Native local playback
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
