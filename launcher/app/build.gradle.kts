import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing key: launcher/keystore.properties (storeFile, storePassword, keyAlias, keyPassword), never committed.
// Without it a release build is signed with the machine's debug key, which installs fine but is not a key the
// in-app updater can follow from one machine to another (see docs/DEVELOPMENT.md, "Releasing").
val keystoreProperties: Properties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Semantic version (MAJOR.MINOR.PATCH), the only place it is set; see CHANGELOG.md. The version code is derived
// from it (1.2.3 -> 10203) so it always grows and installs update in place.
val appVersion: String = "1.0.1"
val appVersionCode: Int = appVersion.split(".").map(String::toInt).let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

android {
    namespace = "com.prototype.lightlauncher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.prototype.lightlauncher"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 strips and optimises the code (AndroidX included): smaller APK, faster start on a slow TV CPU.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Never ship a debuggable APK: run-as would expose the app's private files.
            isDebuggable = false
            // The in-app updater only accepts an APK signed with the same key as the installed app.
            signingConfig = signingConfigs.getByName(if (keystoreProperties.isNotEmpty()) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.recyclerview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.activity.ktx)

    // Test only (never in the APK): plain JVM tests, src/test.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
