plugins { id("com.android.library") }

android {
    namespace = "space.deytt.openflux"
    compileSdk = 35
    ndkVersion = "26.1.10909125"
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments("-DOPENFLUX_GO_EXECUTABLE=${providers.gradleProperty("openfluxGoExecutable").orNull ?: providers.environmentVariable("OPENFLUX_GO_EXECUTABLE").orNull ?: "go"}")
                targets("libopenflux_jni.so")
            }
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

dependencies { testImplementation("junit:junit:4.13.2") }
