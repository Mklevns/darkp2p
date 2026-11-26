// Pure Kotlin/JVM: the darkp2p/v3 wire format and state machines. No Android
// dependency may ever appear here (:checkProtocolPurity).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
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
}

// Vectors shared with the web client: v3.json is generated from the page by
// test/vectors/export.mjs; android-v3.json is generated here for the page to read.
val vectors = rootProject.layout.projectDirectory.file("../test/vectors/v3.json")
val androidVectors = rootProject.layout.projectDirectory.file("../test/vectors/android-v3.json")
val writeVectors = providers.gradleProperty("writeVectors").isPresent

tasks.test {
    useJUnitPlatform()
    inputs.file(vectors).withPropertyName("vectors").withPathSensitivity(PathSensitivity.NONE)
    inputs.property("writeVectors", writeVectors)
    if (writeVectors) outputs.upToDateWhen { false } else inputs.file(androidVectors).withPropertyName("androidVectors").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("darkp2p.vectors", vectors.asFile.absolutePath)
    systemProperty("darkp2p.androidVectors", androidVectors.asFile.absolutePath)
    systemProperty("darkp2p.writeVectors", writeVectors.toString())
}

// The wire format and state machines are the part that must not regress unseen.
kover {
    reports {
        verify {
            rule {
                minBound(90, kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE)
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.named("koverVerify"))
}
