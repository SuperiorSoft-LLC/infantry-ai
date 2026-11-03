plugins {
    // PRE-LOAD PLUGINS FROM CATALOG
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
}

// BUILD-STAMP-1: the version+time+sha string every build carries (rootProject.extra["infantryBuild"]); see the script.
apply(from = rootProject.file("gradle/build-stamp.gradle.kts"))
// PACKAGE-1: what a module's daemon half is, and the check every packaging task runs on it; see the script.
apply(from = rootProject.file("gradle/daemon-halves.gradle.kts"))
// LICENSE-NOTICES-2: SBOM task (generateSbom); see the script.
apply(from = rootProject.file("gradle/sbom.gradle.kts"))

// ---------------------------------------------------------------------------------------------
// SEC-SECRETS-1 guard. Standalone on purpose (NOT wired into `check`): run it explicitly, e.g. from
// the Infantry gate: `./gradlew verifySecretsNotTracked`. Fails when
//   * a git-tracked *.properties file has a secret-looking key with a non-empty value, or a line
//     carrying keytool store/key passwords, or
//   * a keystore / cert container (*.jks, *.p12, *.pfx, *.keystore) is tracked in the git index.
// It reports file:line and the KEY NAME only — never a value. See README.md.
// ---------------------------------------------------------------------------------------------
tasks.register("verifySecretsNotTracked") {
    group = "verification"
    description = "Fails if secrets or keystores are tracked by git (reports key names only)."
    val repoRoot = rootDir
    doLast {
        val proc = ProcessBuilder("git", "ls-files", "-z")
            .directory(repoRoot)
            .redirectErrorStream(false)
            .start()
        val listing = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        check(proc.waitFor() == 0) { "verifySecretsNotTracked: `git ls-files` failed in $repoRoot" }
        val tracked = listing.split('\u0000').filter { it.isNotBlank() }

        val keystoreExt = Regex("""(?i)\.(jks|p12|pfx|keystore)$""")
        // Extensionless / odd-named private keys (id_rsa, *.pem, *.key, *.ppk, .env files).
        val privateKeyName = Regex("""(?i)(^|/)(id_(rsa|dsa|ecdsa|ed25519)|[^/]+\.(pem|key|ppk)|\.env(\..+)?)$""")
        val privateKeyBody = Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----""")
        val confToken = Regex("""(?i)(password|passwd|secret|token|api_?key|private_?key)\s*[=:]\s*["']?[^\s"'#$]{4,}""")
        val secretKey = Regex("""(?i)(password|passwd|secret|token|api_?key|gpr\.key|private_?key)""")
        val keytoolPass = Regex("""(?i)-(src|dest)?(store|key)pass\s+\S""")
        val violations = mutableListOf<String>()

        tracked.filter { keystoreExt.containsMatchIn(it) }
            .forEach { violations += "$it:1 tracked keystore/cert container" }

        // Files whose keys are not credentials (branch-name -> commit-sha registry; branch names such as
        // "sec-secrets-1" would otherwise trip the key regex). Keep this list short and justified.
        val nonSecretPropertyFiles = setOf("superiorSoftLibrary/infantry/preflight/expected-bases.properties")

        tracked.filter { privateKeyName.containsMatchIn(it) }
            .forEach { violations += "$it:1 tracked private-key / .env file" }
        tracked.filter { path ->
            val f = repoRoot.resolve(path)
            f.isFile && f.length() < 1_000_000 && !path.endsWith(".kt") && !path.endsWith(".kts") &&
                runCatching { privateKeyBody.containsMatchIn(f.readText()) }.getOrDefault(false)
        }.forEach { violations += "$it:1 contains a PRIVATE KEY block" }
        tracked.filter { it.endsWith(".conf") }.forEach { path ->
            val f = repoRoot.resolve(path)
            if (!f.isFile) return@forEach
            f.readLines().forEachIndexed { idx, raw ->
                val line = raw.trim()
                if (line.startsWith("#") || line.startsWith("//")) return@forEachIndexed
                if (confToken.containsMatchIn(line)) violations += "$path:${idx + 1} (token-like value in .conf)"
            }
        }

        tracked.filter { it.endsWith(".properties") && it !in nonSecretPropertyFiles }.forEach { path ->
            val f = repoRoot.resolve(path)
            if (!f.isFile) return@forEach
            f.readLines().forEachIndexed { idx, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEachIndexed
                // java.util.Properties: key ends at the first unescaped '=', ':' or whitespace.
                val m = Regex("""^([^=:\s]+)\s*[=:\s]\s*(.*)$""").find(line)
                val key = m?.groupValues?.get(1) ?: line
                val value = m?.groupValues?.get(2)?.trim().orEmpty()
                when {
                    keytoolPass.containsMatchIn(line) ->
                        violations += "$path:${idx + 1} key=$key (inline keytool store/key password)"
                    secretKey.containsMatchIn(key) && value.isNotEmpty() ->
                        violations += "$path:${idx + 1} key=$key (non-empty secret value)"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "verifySecretsNotTracked: ${violations.size} violation(s) — values are never printed:\n" +
                    violations.joinToString("\n") { "  - $it" } +
                    "\nMove secrets to secrets.local.properties (gitignored) and `git rm --cached` keystores. See README.md."
            )
        }
        logger.lifecycle("verifySecretsNotTracked: OK (${tracked.size} tracked files scanned, no secrets/keystores)")
    }
}
