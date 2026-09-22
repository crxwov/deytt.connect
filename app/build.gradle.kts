plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "space.deytt.connect"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "space.deytt.connect"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    // GPL-3.0-or-later shared sing-box engine, pinned for reproducible builds.
    implementation("com.github.singbox-android:libbox:1.14.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
