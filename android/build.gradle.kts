// Root build: plugin versions and the structural checks every phase must keep
// passing (docs/android/TECHNICAL_DESIGN.md §2.1, §5). They read source files and
// build files only, so they run without compiling anything.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kover) apply false
}

// module -> the modules it may depend on.
val allowedEdges: Map<String, Set<String>> = mapOf(
    ":protocol" to emptySet(),
    ":transport" to setOf(":protocol"),
    ":storage" to setOf(":protocol", ":transport"),
    ":service" to setOf(":protocol", ":transport", ":storage"),
    ":app" to setOf(":service", ":storage"),
    ":testing" to setOf(":protocol"),
)

fun sourcesOf(dir: File, main: Boolean): List<File> =
    dir.resolve("src").walkTopDown()
        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
        .filter { !main || it.relativeTo(dir).invariantSeparatorsPath.startsWith("src/main/") }
        .toList()

val moduleDirs: Map<String, File> = allowedEdges.keys.associateWith { projectDir.resolve(it.removePrefix(":")) }

val checkModuleGraph by tasks.registering {
    group = "verification"
    description = "Fails on a project dependency the module graph does not allow."
    val buildFiles = moduleDirs.mapValues { it.value.resolve("build.gradle.kts") }
    val allowed = allowedEdges
    val testOnly = setOf(":testing")
    inputs.files(buildFiles.values)
    doLast {
        val edge = Regex("""^\s*(\w+)\s*\(\s*project\(\s*"(:[\w-]+)"\s*\)""")
        val problems = mutableListOf<String>()
        for ((module, file) in buildFiles) {
            for (line in file.readLines()) {
                val m = edge.find(line) ?: continue
                val (configuration, target) = m.destructured
                val isTest = configuration.contains("test", ignoreCase = true)
                when {
                    target in testOnly && isTest -> Unit
                    target in testOnly -> problems += "$module: $target is test-only ($configuration)"
                    target !in allowed.getValue(module) -> problems += "$module must not depend on $target"
                }
            }
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
    }
}

val checkProtocolPurity by tasks.registering {
    group = "verification"
    description = ":protocol is pure Kotlin/JVM: no Android imports, no dependency outside Kotlin/kotlinx."
    val dir = moduleDirs.getValue(":protocol")
    inputs.dir(dir.resolve("src"))
    inputs.file(dir.resolve("build.gradle.kts"))
    doLast {
        val problems = mutableListOf<String>()
        val banned = Regex("""^\s*import\s+(android|androidx|dalvik|com\.google\.android)\b""")
        for (file in sourcesOf(dir, main = false)) {
            file.readLines().forEachIndexed { i, line ->
                if (banned.containsMatchIn(line)) problems += "${file.relativeTo(dir)}:${i + 1}: ${line.trim()}"
            }
        }
        val build = dir.resolve("build.gradle.kts").readText()
        if (Regex("""com\.android|libs\.plugins\.android""").containsMatchIn(build)) {
            problems += "protocol/build.gradle.kts applies an Android plugin"
        }
        val dependency = Regex("""^\s*(?:api|implementation|compileOnly|runtimeOnly)\s*\((.+)\)\s*$""")
        val pure = Regex("""^(libs\.kotlinx\.[\w.]+|kotlin\("[\w-]+"\))$""")
        for (line in build.lines()) {
            val target = dependency.find(line)?.groupValues?.get(1)?.trim() ?: continue
            if (!pure.matches(target)) problems += "protocol/build.gradle.kts: dependency $target is not Kotlin/kotlinx"
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
    }
}

val checkForbiddenApis by tasks.registering {
    group = "verification"
    description = "Fails on APIs that can leak: logging, network calls that bypass the Tor-aware client, WebView."
    val dirs = moduleDirs.filterKeys { it != ":testing" }
    dirs.values.forEach { inputs.dir(it.resolve("src")).optional() }
    doLast {
        // pattern -> why, and the files (path suffix) that are the one allowed home of that API.
        val rules = listOf(
            Triple(Regex("""\bandroid\.util\.Log\b|\bLog\.(v|d|i|w|e|wtf)\("""), "no logging: room IDs, tokens and file names must not reach logcat", emptyList<String>()),
            Triple(Regex("""\bprintln\(|\bprintStackTrace\("""), "no console output", emptyList()),
            Triple(Regex("""\bHttpURLConnection\b|\bHttpsURLConnection\b|\.openConnection\(|\.openStream\("""), "use ServerApi / the shared OkHttpClient, which follows Tor mode", emptyList()),
            Triple(Regex("""\bInetAddress\.(getByName|getAllByName)\(|\bDns\.SYSTEM\b"""), "system DNS only in ClearnetDns: Tor mode must never resolve locally", listOf("/ClearnetDns.kt")),
            Triple(Regex("""\bandroid\.webkit\b|\bWebView\b"""), "no WebView", emptyList()),
            Triple(Regex("""\bCustomTabsIntent\b"""), "Custom Tabs only in CheckoutLauncher: never in Tor mode", listOf("/CheckoutLauncher.kt")),
        )
        val problems = mutableListOf<String>()
        for ((_, dir) in dirs) {
            for (file in sourcesOf(dir, main = true)) {
                val path = file.invariantSeparatorsPath
                file.readLines().forEachIndexed { i, line ->
                    if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
                    for ((pattern, why, homes) in rules) {
                        if (pattern.containsMatchIn(line) && homes.none { path.endsWith(it) }) {
                            problems += "${file.relativeTo(projectDir)}:${i + 1}: $why"
                        }
                    }
                }
            }
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
    }
}

val checkDependencies by tasks.registering {
    group = "verification"
    description = "Exact versions only, and no proprietary Google SDKs (F-Droid)."
    val catalog = projectDir.resolve("gradle/libs.versions.toml")
    val buildFiles = moduleDirs.values.map { it.resolve("build.gradle.kts") }
    inputs.file(catalog)
    inputs.files(buildFiles)
    doLast {
        val problems = mutableListOf<String>()
        val proprietary = Regex("""com\.google\.android\.gms|com\.google\.firebase|com\.google\.mlkit|com\.google\.android\.play""")
        val text = catalog.readText()
        Regex("""^([\w-]+)\s*=\s*"([^"]*)"\s*$""", RegexOption.MULTILINE).findAll(text.substringAfter("[versions]").substringBefore("[libraries]")).forEach {
            val (name, version) = it.destructured
            if (!Regex("""\d[\w.]*(-[\w.]+)?""").matches(version)) problems += "libs.versions.toml: $name = \"$version\" is not an exact version"
        }
        for (file in buildFiles + catalog) {
            file.readLines().forEachIndexed { i, line ->
                if (proprietary.containsMatchIn(line)) problems += "${file.relativeTo(projectDir)}:${i + 1}: proprietary dependency"
                if (Regex("""["'][\w.-]+:[\w.-]+:[^"']*["']""").containsMatchIn(line) && file != catalog) {
                    problems += "${file.relativeTo(projectDir)}:${i + 1}: declare dependencies in gradle/libs.versions.toml"
                }
            }
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
    }
}

val checkTests by tasks.registering {
    group = "verification"
    description = "Fails on test methods JUnit would silently skip."
    val dirs = moduleDirs
    dirs.values.forEach { inputs.dir(it.resolve("src")).optional() }
    doLast {
        // JUnit 5 ignores a test method that returns a value. `= runBlocking { ... }`
        // returns whatever its last expression is, so the test would never run.
        val risky = Regex("""\)\s*=\s*(runBlocking|runTest)\s*(\(|\{)""")
        val problems = mutableListOf<String>()
        for ((_, dir) in dirs) {
            for (file in sourcesOf(dir, main = false)) {
                if ("/src/main/" in file.invariantSeparatorsPath) continue
                file.readLines().forEachIndexed { i, line ->
                    if (risky.containsMatchIn(line)) problems += "${file.relativeTo(projectDir)}:${i + 1}: write runBlocking<Unit> { } so JUnit runs the test"
                }
            }
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n"))
    }
}

val checkStructure by tasks.registering {
    group = "verification"
    description = "All structural checks."
    dependsOn(checkModuleGraph, checkProtocolPurity, checkForbiddenApis, checkDependencies, checkTests)
}

subprojects {
    plugins.withId("base") {
        tasks.named("check") { dependsOn(rootProject.tasks.named("checkStructure")) }
    }
}
