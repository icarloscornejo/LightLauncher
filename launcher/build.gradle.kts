buildscript {
    dependencies {
        // AGP 9 compiles Kotlin itself but bundles its own Kotlin Gradle plugin version. Putting the
        // catalog's version on the classpath pins the compiler (check with ./gradlew buildEnvironment).
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
