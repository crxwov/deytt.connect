plugins {
    id("com.android.library")
}

android {
    namespace = "org.amnezia.awg.tunnel"
    compileSdk = 35
    ndkVersion = "26.1.10909125"

    defaultConfig {
        minSdk = 24
        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_PACKAGE_NAME=space.deytt.connect",
                    "-DGRADLE_USER_HOME=${project.gradle.gradleUserHomeDir}",
                )
                targets("libwg-go.so", "libwg.so", "libwg-quick.so")
            }
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir("../third_party/amneziawg-android/tunnel/src/main/java")
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../third_party/amneziawg-android/tunnel/tools/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // The upstream module supports API 24 through guarded/desugared paths.
        disable += "NewApi"
        disable += "LongLogTag"
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.collection:collection:1.5.0")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}
