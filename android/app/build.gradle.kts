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

// Every permission a build asks for, written down. A library's manifest can add one
// silently (libwebrtc's asks for the microphone, which :transport strips); with this,
// that is a failed build with the name in it, not a surprise in a store listing.
val common = setOf(
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE", // which network is in use; a VPN on a relay fallback
    "android.permission.CAMERA", // QR scanning, asked for on use
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.WAKE_LOCK",
    "android.permission.USE_BIOMETRIC", // app lock
    "android.permission.USE_FINGERPRINT", // the same, for androidx.biometric on old releases
    "android.permission.RECEIVE_BOOT_COMPLETED", // WorkManager: claim polling after a restart
    "com.darkp2p.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", // androidx.core's own, signature-level
)
val allowedPermissions = mapOf(
    // Direct connections on the same Wi-Fi (Android 17); only the flavor with WebRTC has any.
    "full" to common + "android.permission.ACCESS_LOCAL_NETWORK",
    "libre" to common,
)

abstract class CheckPermissions : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val allowed: SetProperty<String>

    @TaskAction
    fun check() {
        val asked = Regex("""<uses-permission(?:-sdk-\d+)?\s[^>]*?android:name="([^"]+)"""").findAll(manifest.get().asFile.readText()).map { it.groupValues[1] }.toSortedSet()
        val extra = asked - allowed.get()
        val gone = allowed.get() - asked
        if (extra.isNotEmpty() || gone.isNotEmpty()) {
            throw GradleException(
                "The merged manifest's permissions differ from the list in app/build.gradle.kts.\n" +
                    (if (extra.isNotEmpty()) "  asked for but not listed: ${extra.joinToString()}\n" else "") +
                    (if (gone.isNotEmpty()) "  listed but not asked for: ${gone.sorted().joinToString()}\n" else "") +
                    "Change the list only on purpose: it is what the app asks of its user.",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<CheckPermissions>("check${variant.name.replaceFirstChar(Char::uppercase)}Permissions") {
            group = "verification"
            description = "Fails if the ${variant.name} build asks for a permission that is not on the list, or no longer asks for one that is."
            manifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            allowed.set(allowedPermissions.getValue(variant.flavorName ?: error("no flavor")))
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(task) }
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
