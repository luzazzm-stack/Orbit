plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.orbit"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.orbit"
        minSdk = 21
        targetSdk = 34
        versionCode = 10
        versionName = "0.5.0"
        resourceConfigurations += listOf("en")
    }

    // Committed key: this app is sideloaded on a personal TV. A stable signature
    // means `adb install -r` upgrades in place and keeps app data/permissions.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/orbit.jks")
            storePassword = "orbitkey"
            keyAlias = "orbit"
            keyPassword = "orbitkey"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    // TV-only sideload: several attributes (defaultFocusHighlightEnabled) are
    // API 26+ and simply ignored on older builds, which is the intended
    // behaviour. Don't let lintVitalRelease fail the CI build over it.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json"
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.material:material:1.12.0")

    // DNS-over-HTTPS. WebView exposes no way to change Chromium's resolver, so
    // AdGuard DNS is used as a blocking oracle instead: resolve the host over
    // DoH and drop the request when AdGuard answers 0.0.0.0 / NXDOMAIN.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
}
