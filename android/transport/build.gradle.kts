plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.darkp2p.transport"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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

// The interop tests take minutes and do not differ between build types: run them once.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false }
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

    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
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
