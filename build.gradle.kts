plugins {
    id("com.android.application") version "9.1.1" apply false
}

tasks.register("verifyRepositoryHygiene") {
    group = "verification"
    description = "Prevents local reverse-engineering, diagnostic and build artifacts from being tracked."

    doLast {
        if (!file(".git").exists()) {
            logger.info("Skipping repository hygiene check outside a Git checkout.")
            return@doLast
        }

        val process = ProcessBuilder("git", "ls-files", "-z")
            .directory(projectDir)
            .redirectErrorStream(true)
            .start()
        val output = String(process.inputStream.readBytes(), Charsets.UTF_8)
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "Failed to list tracked files for repository hygiene check:\n$output"
        }

        val forbiddenPrefixes = listOf(
            ".gradle/",
            ".idea/",
            ".kotlin/",
            "artifacts/",
            "build/",
            "docs/",
            "log/",
            "app/build/",
            "app/release/",
        )
        val forbiddenRootFile = Regex(
            pattern = """^[^/]+\.(apk|ap_|aab|aar|jks|keystore|log)$""",
            option = RegexOption.IGNORE_CASE,
        )
        val tracked = output
            .split('\u0000')
            .map { it.replace('\\', '/') }
            .filter { it.isNotBlank() }
        val forbidden = tracked.filter { path ->
            path == "local.properties" ||
                path.endsWith("/output-metadata.json") ||
                forbiddenRootFile.matches(path) ||
                forbiddenPrefixes.any { prefix -> path == prefix.removeSuffix("/") || path.startsWith(prefix) }
        }
        check(forbidden.isEmpty()) {
            "Local-only files must not be tracked or submitted:\n" +
                forbidden.sorted().joinToString("\n")
        }
    }
}

tasks.register("verifyArchitecture") {
    group = "verification"
    description = "Checks SaltPlayerProvider libxposed metadata, Lyricon mapping and architecture boundaries."

    doLast {
        fun sourceFilesUnder(root: java.io.File): Sequence<java.io.File> {
            if (!root.exists()) {
                return emptySequence()
            }
            return root.walkTopDown().filter { file ->
                file.isFile && file.extension in setOf("kt", "kts", "java", "xml", "list", "prop", "pro")
            }
        }

        fun readLinesUtf8(file: java.io.File): List<String> = file.readLines(Charsets.UTF_8)

        fun readTextUtf8(file: java.io.File): String = file.readText(Charsets.UTF_8)

        fun entries(file: java.io.File): List<String> {
            return readLinesUtf8(file)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        }

        fun relativePath(file: java.io.File): String = file.relativeTo(projectDir).invariantSeparatorsPath

        val javaInit = file("app/src/main/resources/META-INF/xposed/java_init.list")
        val expectedEntry = "com.xiyunmn.salthook.xposed.SaltPlayerLyriconModule"
        check(entries(javaInit) == listOf(expectedEntry)) {
            "java_init.list must point only to $expectedEntry"
        }

        val scope = file("app/src/main/resources/META-INF/xposed/scope.list")
        check(entries(scope) == listOf("com.salt.music")) {
            "scope.list must contain only com.salt.music"
        }

        val moduleProp = readTextUtf8(file("app/src/main/resources/META-INF/xposed/module.prop"))
        check(moduleProp.contains("id=com.xiyunmn.salthook")) {
            "module.prop must keep id=com.xiyunmn.salthook"
        }
        check(moduleProp.contains("minApiVersion=101") && moduleProp.contains("targetApiVersion=101")) {
            "module.prop must target libxposed API 101"
        }

        val manifest = readTextUtf8(file("app/src/main/AndroidManifest.xml"))
        check(manifest.contains("android:name=\"lyricon_module\"") && manifest.contains("android:value=\"true\"")) {
            "AndroidManifest.xml must keep lyricon_module metadata"
        }

        check(!file("app/src/main/assets/xposed_init").exists()) {
            "Legacy assets/xposed_init is not allowed"
        }

        val architectureFiles = sourceFilesUnder(file("app/src")) +
            sequenceOf(file("app/build.gradle.kts"), file("app/proguard-rules.pro"))
        val banned = Regex(
            listOf(
                "de\\.robv\\.android\\.xposed",
                "IXposedHookLoadPackage",
                "XposedBridge",
                "XposedHelpers",
                "XSharedPreferences",
                "assets/xposed_init",
                "\\bxposed_init\\b",
                "io\\.github\\.libxposed:service",
                "getRemotePreferences",
            ).joinToString("|"),
        )
        val bannedMatches = architectureFiles.flatMap { source ->
            readLinesUtf8(source).asSequence().mapIndexedNotNull { index, line ->
                if (banned.containsMatchIn(line)) {
                    "${relativePath(source)}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }.toList()
        check(bannedMatches.isEmpty()) {
            "Banned legacy/service API references found:\n" + bannedMatches.take(80).joinToString("\n")
        }

        val extractor = readTextUtf8(file("app/src/main/kotlin/com/xiyunmn/salthook/xposed/SaltPlayerExtractors.kt"))
        check(extractor.contains("word.text.isNotEmpty()")) {
            "SaltPlayerExtractors must keep pure-space lyric words; do not filter words with isBlank()."
        }
        check(!extractor.contains("word.text.isNotBlank()") && !extractor.contains("isBlank(word.text)")) {
            "Pure-space lyric words must not be filtered as blank."
        }

        val mapper = readTextUtf8(file("app/src/main/kotlin/com/xiyunmn/salthook/provider/LyriconSongMapper.kt"))
        check(mapper.contains("translation") && mapper.contains("translationWords")) {
            "LyriconSongMapper must map translations through standard translation fields."
        }
        check(!mapper.contains(".secondary") && !mapper.contains("secondary =")) {
            "Do not route SaltPlayer translations through RichLyricLine.secondary."
        }

        val diagnostics = readTextUtf8(file("app/src/main/kotlin/com/xiyunmn/salthook/diagnostics/SaltDiagnostics.kt"))
        check(diagnostics.contains("fun enabled(): Boolean = false")) {
            "Detailed SaltDiagnostics tracing must stay disabled by default."
        }
        check(!diagnostics.contains("FileOutputStream") && !diagnostics.contains("saltlyricon-diag.log")) {
            "Long-running host file diagnostics must not be restored."
        }

        val appBuild = readTextUtf8(file("app/build.gradle.kts"))
        check(appBuild.contains("""compileOnly("io.github.libxposed:api:101.0.0")""")) {
            "libxposed API 101 must stay compileOnly."
        }
        check(appBuild.contains("""implementation("io.github.proify.lyricon:provider:0.1.70")""")) {
            "Lyricon provider dependency version changed; update docs if intentional."
        }

        val versionName = Regex("""versionName\s*=\s*"([^"]+)"""")
            .find(appBuild)
            ?.groupValues
            ?.get(1)
            ?: error("Cannot read versionName from app/build.gradle.kts")
        val versionCode = Regex("""versionCode\s*=\s*(\d+)""")
            .find(appBuild)
            ?.groupValues
            ?.get(1)
            ?: error("Cannot read versionCode from app/build.gradle.kts")
        check(moduleProp.contains("version=$versionName") && moduleProp.contains("versionCode=$versionCode")) {
            "module.prop version/versionCode must match app/build.gradle.kts."
        }

        val strings = readTextUtf8(file("app/src/main/res/values/strings.xml"))
        val compatibility = Regex("""当前宿主兼容版本：([^。<\n]+)""")
            .find(strings)
            ?.groupValues
            ?.get(1)
            ?: error("Cannot read compatibility range from strings.xml")
        val readme = readTextUtf8(file("README.md"))
        check(readme.contains(compatibility)) {
            "README.md must contain current compatibility range: $compatibility"
        }

        val mojibakePattern = Regex(
            listOf(
                "\uFFFD",
                "\u00C3.",
                "\u00C2.",
                "\u951B",
                "\u9286",
                "\u9225",
                "\u6D93",
                "\u7ED7",
                "\u9359",
                "\u9428",
                "\u74A7",
                "\u6434",
                "\u6D63",
                "\u95C8",
                "\u59AF",
                "\u93B5",
                "\u6748",
                "\u6FC2",
                "\u93C2",
                "\u608A",
                "\u9354",
                "\u7ECB",
                "\u7F03",
                "\u93C3",
                "\u93C4",
                "\u741B",
                "\u9422",
                "\u701B",
                "\u947E",
                "\u95AB",
            ).joinToString("|"),
        )
        val mojibakeMatches = sourceFilesUnder(file("app/src"))
            .flatMap { source ->
                readLinesUtf8(source).asSequence().mapIndexedNotNull { index, line ->
                    if (mojibakePattern.containsMatchIn(line)) {
                        "${relativePath(source)}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()
        check(mojibakeMatches.isEmpty()) {
            "Possible mojibake or non-UTF-8 replacement text found:\n" +
                mojibakeMatches.take(80).joinToString("\n")
        }
    }
}

gradle.projectsEvaluated {
    tasks.findByPath(":app:preBuild")?.dependsOn(
        tasks.named("verifyRepositoryHygiene"),
        tasks.named("verifyArchitecture"),
    )
}
