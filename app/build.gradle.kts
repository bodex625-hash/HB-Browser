plugins {
    id("com.android.application")
    id("com.cefrium") version "0.19.0"
}

android {
    namespace = "com.hb.browser"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hb.browser"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1-chromium-prototype"
        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.cefrium:cefrium-sdk:0.19.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.browser:browser:1.8.0")
}
