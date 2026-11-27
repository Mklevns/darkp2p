plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.darkp2p.transport"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    // Same dimension as :app. `full` carries the prebuilt libwebrtc; `libre`
    // (F-Droid) has no prebuilt native code and therefore no WebRTC yet.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") { dimension = "distribution" }
        create("libre") { dimension = "distribution" }
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

// The interop tests take minutes and do not differ between variants: run them once.
androidComponents {
    beforeVariants { variant ->
        if (variant.buildType == "release" || variant.flavorName == "libre") {
            variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
        }
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
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    "fullImplementation"(libs.webrtc)

    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    // A desktop build of libwebrtc: the tests that use it run on Linux x86-64 only.
    testImplementation(libs.webrtc.java)
    testRuntimeOnly(variantOf(libs.webrtc.java) { classifier("linux-x86_64") })
    testImplementation(libs.kotlinx.coroutines.test)
}

// The unit tests run on the host JVM against a real server.js and the real page
// script (test/interop/peer.mjs), so they need Node and the repository root.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    val repo = rootProject.layout.projectDirectory.dir("..")
    systemProperty("darkp2p.repo", repo.asFile.absolutePath)
    inputs.files(repo.file("server.js"), repo.file("public/index.html"), repo.file("test/interop/peer.mjs"), repo.file("test/helpers/page.mjs"))
        .withPropertyName("webClient").withPathSensitivity(PathSensitivity.NONE)
    testLogging { events("failed") }
}
