import java.util.Properties

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
        versionCode = 4
        versionName = "0.4.0"
    }

    // Release builds for the family's own phones are signed with the local debug key,
    // like the builds already on them. The APKs published on GitHub get the project's
    // key instead, only when asked: ./gradlew assembleRelease -Ppublish. That key lives
    // outside git, in .local/keys/release.properties (storeFile, storePassword,
    // keyAlias, keyPassword).
    val publish = project.hasProperty("publish")
    val releaseKey = rootDir.resolve("../.local/keys/release.properties")
    if (publish) check(releaseKey.isFile) { "-Ppublish needs $releaseKey" }
    signingConfigs {
        if (publish) create("release") {
            val p = Properties().apply { releaseKey.inputStream().use { load(it) } }
            storeFile = releaseKey.parentFile.resolve(p.getProperty("storeFile"))
            storePassword = p.getProperty("storePassword")
            keyAlias = p.getProperty("keyAlias")
            keyPassword = p.getProperty("keyPassword")
        }
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
            // Installed by hand, not through Play: an APK installs over another only when
            // both are signed with the same key, so never mix the two.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
