package com.superiorsoftllc.agenthq.worktree

import java.io.File
import java.util.Properties

/**
 * A fresh ticket worktree has no `local.properties` (it is untracked, so git never carries it), and Gradle's
 * `compileAndroidMain` then fails deep inside with "SDK location not found". The daemon puts the SDK location
 * in place itself, at worktree creation, so no worker has to remember it.
 *
 * The SDK location, first hit wins:
 *  1. the main checkout's own `local.properties` `sdk.dir` (the user's configured SDK; copied as-is),
 *  2. the setting [sdkSetting] (`AGENTHQ_ANDROID_SDK`), then `ANDROID_HOME`, then `ANDROID_SDK_ROOT`.
 * A found directory is written to `<worktree>/local.properties` as `sdk.dir=`. An existing file is never
 * overwritten. When nothing resolves, [provision] returns ONE clear message instead of a Gradle failure later.
 * `local.properties` is git-ignored; nothing here is committed.
 * Only a Gradle project (a `settings.gradle(.kts)` or `build.gradle(.kts)` at the main checkout's root) is provisioned:
 * any other repository needs no SDK, so it gets neither a file nor a warning.
 */
class AndroidSdkProvision(
    private val env: (String) -> String? = System::getenv,
) {

    /** @return a warning for the start's answer, or null when the SDK location is in place. */
    fun provision(mainRoot: File, worktree: File): String? {
        val target = File(worktree, LOCAL_PROPERTIES)
        if (target.exists() || !isGradleProject(mainRoot)) return null
        val sdk = locate(mainRoot)
            ?: return "no Android SDK found: set $SETTING (or ANDROID_HOME), or add sdk.dir to " +
                "${File(mainRoot, LOCAL_PROPERTIES).path}; Android compile tasks will fail in this worktree"
        return runCatching {
            // Properties escapes backslashes and colons, so a Windows path round-trips through Gradle.
            val props = Properties().apply { setProperty("sdk.dir", sdk.path) }
            target.outputStream().use { props.store(it, "Written by AgentHQ at worktree creation; untracked") }
            null
        }.getOrElse { "could not write ${target.path}: ${it.message}" }
    }

    private fun isGradleProject(root: File): Boolean = GRADLE_FILES.any { File(root, it).isFile }

    private fun locate(mainRoot: File): File? {
        val fromMain = File(mainRoot, LOCAL_PROPERTIES).takeIf { it.isFile }?.let { file ->
            runCatching { Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir") }.getOrNull()
        }
        return sequenceOf(fromMain, env(SETTING), env("ANDROID_HOME"), env("ANDROID_SDK_ROOT"))
            .mapNotNull { it?.trim()?.takeIf { p -> p.isNotEmpty() } }
            .map(::File)
            .firstOrNull { it.isDirectory }
    }

    companion object {
        const val SETTING = "AGENTHQ_ANDROID_SDK"
        private const val LOCAL_PROPERTIES = "local.properties"
        private val GRADLE_FILES = listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle")
    }
}
