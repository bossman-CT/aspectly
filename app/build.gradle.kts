plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.bossmanct.aspectly"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.bossmanct.aspectly"
        // Android 11 is the floor: wireless debugging pairing does not exist below it.
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
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
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.libadb.android)
    implementation(libs.bouncycastle.pkix)
    // libadb's SslUtils looks for org.conscrypt.OpenSSLProvider and uses it if present.
    // Without it, it falls back to reflecting into the platform's hidden Conscrypt,
    // whose exportKeyingMaterial signature no longer matches on Android 17.
    implementation(libs.conscrypt.android)

    debugImplementation(libs.androidx.ui.tooling)
}
