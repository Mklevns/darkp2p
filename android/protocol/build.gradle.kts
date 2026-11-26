// Pure Kotlin/JVM: the darkp2p/v3 wire format and state machines. No Android
// dependency may ever appear here (:checkProtocolPurity).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.serialization.json)
}

// The shared vectors are generated from the web client by test/vectors/export.mjs.
val vectors = rootProject.layout.projectDirectory.file("../test/vectors/v3.json")

tasks.test {
    useJUnitPlatform()
    inputs.file(vectors).withPropertyName("vectors").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("darkp2p.vectors", vectors.asFile.absolutePath)
}
