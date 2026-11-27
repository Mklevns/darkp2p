plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.darkp2p.storage"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        // :transport differs per distribution; this module is the same in both and
        // compiles against what both have in common.
        missingDimensionStrategy("distribution", "libre")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // These fail a build because of what was released since, not because of
        // a change here. Versions move deliberately, with the checksums.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    explicitApi()
}

dependencies {
    api(project(":protocol"))
    implementation(project(":transport"))
}
