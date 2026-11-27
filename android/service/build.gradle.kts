plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.darkp2p.service"
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

// Tests do not differ between build types: run them once.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false }
}

dependencies {
    api(project(":protocol"))
    api(project(":transport"))
    api(project(":storage"))
    // The service runs its bookkeeping on the main thread (Dispatchers.Main).
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(testFixtures(project(":transport")))
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// As in :transport: the tests use the real server.js and page through test/interop/peer.mjs.
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    val repo = rootProject.layout.projectDirectory.dir("..")
    systemProperty("darkp2p.repo", repo.asFile.absolutePath)
    inputs.files(repo.file("server.js"), repo.file("public/index.html"), repo.file("test/interop/peer.mjs"), repo.file("test/helpers/page.mjs"))
        .withPropertyName("webClient").withPathSensitivity(PathSensitivity.NONE)
    testLogging { events("failed") }
}
