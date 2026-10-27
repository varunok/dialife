plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi) // screenshot tests: ./gradlew :app:verifyRoborazziDebug
}

android {
    namespace = "com.lumenarcanus.dialife"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lumenarcanus.dialife"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
    }

    androidResources {
        // per-app language (Android 13+ settings); generates locales_config from values-* folders
        generateLocaleConfig = true
        // keep only languages we translate ourselves, so libraries do not leave screens half-translated;
        // add a language here together with its values-xx folder
        localeFilters += listOf("en", "uk", "es")
    }

    buildTypes {
        release {
            // Family app installed by hand, not through Play: signed with the same key as the
            // debug builds already on the phones, so it installs over them and keeps the
            // connection. A copy of the key is in .local/keys; without it no update installs.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true // Robolectric renders our real resources and fonts
    }

    buildFeatures {
        buildConfig = true // version shown in settings
        compose = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat) // per-app language on Android 12 too
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.glance.appwidget) // home screen widget
    implementation(libs.play.services.code.scanner) // Google's scanner UI: no camera permission in our app
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
