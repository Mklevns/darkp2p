plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.darkp2p.app"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) { minorApiLevel = libs.versions.compileSdkMinor.get().toInt() }
    }

    defaultConfig {
        applicationId = "com.darkp2p.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        // The server new rooms are created on, and the only one joined without asking first.
        buildConfigField("String", "DEFAULT_ORIGIN", "\"https://darkp2p.com\"")
    }

    // full: GitHub/Play (prebuilt libwebrtc, embedded Tor option).
    // libre: F-Droid (from-source native code only, Orbot, no autoVerify App Links).
    flavorDimensions += "distribution"
    productFlavors {
        create("full") { dimension = "distribution" }
        create("libre") { dimension = "distribution" }
    }

    buildTypes {
        debug {
            // For testing against a server on the development machine:
            //   ./gradlew :app:installFullDebug -Pdarkp2p.origin=http://127.0.0.1:3000
            // (with `adb reverse tcp:3000 tcp:3000`). Debug builds only; a release always uses the real server.
            providers.gradleProperty("darkp2p.origin").orNull?.let { origin ->
                require(Regex("^https?://[a-z0-9.-]+(:\\d{1,5})?$").matches(origin)) { "darkp2p.origin must look like http://127.0.0.1:3000" }
                buildConfigField("String", "DEFAULT_ORIGIN", "\"$origin\"")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
}

// Tests do not differ between flavors or build types: run them once.
androidComponents {
    beforeVariants { variant ->
        if (variant.buildType == "release" || variant.flavorName == "libre") {
            variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
        }
    }
}

dependencies {
    implementation(project(":service"))
    implementation(project(":storage"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // QR codes: generated and read on the device, with no Google service involved.
    implementation(libs.zxing.core)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    implementation(libs.browser)
    implementation(libs.biometric)
    implementation(libs.fragment)

    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed") }
}
