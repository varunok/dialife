import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.roborazzi) // screenshot tests: ./gradlew :app:verifyRoborazziDebug
    alias(libs.plugins.ktlint) // Kotlin style: ./gradlew :app:ktlintCheck (rules in android/.editorconfig)
}

// The version comes from one place, the newest git tag vX.Y.Z: on the tag itself it is "X.Y.Z";
// n commits after it "X.Y.Z-n-g<commit>", "-dirty" with uncommitted changes, so a phone always
// shows which build it runs. The code grows with every build: (X·10000 + Y·100 + Z)·100 + n,
// starting at 70000 for 0.7.0, above every hand-set code before it. Without git: "0.0.0-dev".
data class AppVersion(val name: String, val code: Int)

val appVersion: AppVersion = providers.exec {
    commandLine("git", "describe", "--tags", "--match", "v[0-9]*.[0-9]*.[0-9]*", "--long", "--dirty")
    isIgnoreExitValue = true
}.standardOutput.asText.map { out ->
    val m = Regex("""^v(\d+)\.(\d+)\.(\d+)-(\d+)-g([0-9a-f]+)(-dirty)?$""").find(out.trim())
        ?: return@map AppVersion("0.0.0-dev", 1)
    val (major, minor, patch, ahead, commit) = m.destructured
    val dirty = m.groupValues[6].isNotEmpty()
    val base = "$major.$minor.$patch"
    val name = if (ahead == "0" && !dirty) base else "$base-$ahead-g$commit" + if (dirty) "-dirty" else ""
    val code = (major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()) * 100 + ahead.toInt().coerceAtMost(99)
    AppVersion(name, code)
}.get()

android {
    namespace = "com.lumenarcanus.dialife"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lumenarcanus.dialife"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersion.code
        versionName = appVersion.name
    }

    // Two builds, never mixed: "release" for the family's own phones, signed with the
    // local debug key like the builds already on them (dl app); "publish" for the APKs
    // on GitHub, signed with the project's key (dl app-publish), in its own folder
    // (apk/publish). That key lives outside git, in .local/keys/release.properties
    // (storeFile, storePassword, keyAlias, keyPassword); without it no publish build.
    val releaseKey = rootDir.resolve("../.local/keys/release.properties")
    signingConfigs {
        if (releaseKey.isFile) {
            create("project") {
                val p = Properties().apply { releaseKey.inputStream().use { load(it) } }
                storeFile = releaseKey.parentFile.resolve(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
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
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("publish") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.findByName("project") // none without the key: the build stops
            matchingFallbacks += "release"
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
    implementation(libs.androidx.navigation.compose) // one back-stack entry per screen: its view model lives and dies with it
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
    testImplementation(libs.kotlinx.coroutines.test) // view models on a test dispatcher
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
