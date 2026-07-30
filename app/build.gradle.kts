plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "kz.invisibleshield.app"
    compileSdk = 34
    // Пин версии — на машине разработчика через SDK Manager стоят сразу две
    // (26.1.10909125 и 30.0.15729638); без явного pin AGP может взять любую
    // или попытаться докачать третью. r26 — куда более обкатанная версия для
    // llama.cpp/CMake NDK-сборок, чем совсем свежая r30.
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "kz.invisibleshield.app"
        // ROLE_CALL_SCREENING (RoleManager) требует API 29 (ADR-011) — минимальная
        // версия зафиксирована по решённому подходу к звонковому каналу, не произвольно.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-skeleton"

        // ADR-002: только arm64-v8a — armeabi-v7a под старые устройства сознательно
        // отложен (влияет на размер APK и заявленный охват, см. ADR-002 "Требует
        // верификации"), не решено, пока бенч не даст фактических цифр.
        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
