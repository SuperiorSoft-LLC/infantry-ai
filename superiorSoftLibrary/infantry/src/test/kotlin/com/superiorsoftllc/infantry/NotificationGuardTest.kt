// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * NOTIFY-CORE-1 (docs/wiki/Notifications.md): there is ONE notification system. Feature and plugin code posts on the bus
 * (`Notifications.notify`, `DaemonApi.notifications`, `ih.call("notify", ...)`) and never draws its own toast, snackbar or
 * native dialog. This guard greps every main source set for the known patterns. A hit outside [allowed] fails the build;
 * an [allowed] entry that no longer matches fails too, so the list only shrinks.
 */
class NotificationGuardTest {
    private class Pattern(val name: String, val regex: Regex, val why: String)

    private val patterns = listOf(
        Pattern("JOptionPane", Regex("""\bJOptionPane\b"""), "a native Swing dialog"),
        Pattern("Snackbar", Regex("""\bSnackbar(Host|HostState)?\b"""), "a snackbar of its own"),
        Pattern("Toast.makeText", Regex("""\bToast\.makeText\b"""), "an Android toast of its own"),
        Pattern("SystemTray", Regex("""\b(SystemTray|TrayIcon)\b"""), "an OS notification outside the OS-channel presenter"),
        Pattern("ToastHost", Regex("""\bToastHost\s*\("""), "a toast host outside the core default presenter"),
        Pattern("ToastEvent", Regex("""\bToastEvent\s*\("""), "a toast built outside core (post a Notification instead)"),
    )

    /** pattern name -> path prefixes (repo-relative, `/`) where it is allowed, each with its reason. */
    private val allowed: Map<String, Map<String, String>> = mapOf(
        "SystemTray" to mapOf(
            "plugins/official/Infantry_Notify/" to "Infantry_Notify IS the OS-channel presenter (NotificationPresenter for `os`).",
        ),
        "ToastHost" to mapOf(
            "superiorSoftLibrary/infantryUiCore/" to "the core default in-app presenter (ShellToast) and its definition.",
        ),
        "ToastEvent" to mapOf(
            "superiorSoftLibrary/infantryUiCore/" to "core's toast model, the core presenter and the reducer's own notices.",
        ),
        "JOptionPane" to mapOf(
            "superiorSoftLibrary/infantryUi/src/main/kotlin/com/superiorsoftllc/infantry/ui/WindowActivation.kt" to
                "a second launch whose running instance cannot be reached: a process with no window and no bus, about to exit.",
        ),
        "Toast.makeText" to mapOf(
            "plugins/official/Infantry_Updater/androidSrc/AndroidSelfUpdate.kt" to
                "Android self-update runs before/without a shell; follow-up ticket moves it onto the bus.",
        ),
    )

    private val skipDirs = setOf("build", ".gradle", "test", "jvmTest", "commonTest", "androidTest", "androidUnitTest", "wasmJsTest", "testFixtures", "node_modules")

    private fun root(): File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "superiorSoftLibrary").isDirectory && File(it, "plugins").isDirectory }

    private fun sources(root: File): Sequence<Pair<String, File>> =
        listOf("superiorSoftLibrary", "plugins", "samples", "templates").asSequence().map { File(root, it) }.filter { it.isDirectory }
            .flatMap { top -> top.walkTopDown().onEnter { it.name !in skipDirs }.filter { it.isFile && (it.extension == "kt" || it.extension == "java") } }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }

    @Test
    fun `feature and plugin code draws no toast, snackbar or native dialog of its own`() {
        val root = root()
        val hits = mutableListOf<String>()
        val used = mutableSetOf<Pair<String, String>>()
        sources(root).forEach { (path, file) ->
            val lines = file.readLines()
            patterns.forEach { p ->
                lines.forEachIndexed { i, line ->
                    if (!p.regex.containsMatchIn(line)) return@forEachIndexed
                    val allow = allowed[p.name].orEmpty().keys.firstOrNull { path.startsWith(it) }
                    if (allow != null) used += p.name to allow else hits += "$path:${i + 1}: ${p.why} (${p.name}): ${line.trim().take(120)}"
                }
            }
        }
        assertTrue(hits.isEmpty(), "Post on the notification bus instead (docs/wiki/Notifications.md):\n" + hits.joinToString("\n"))
        val stale = allowed.flatMap { (p, m) -> m.keys.map { p to it } }.filter { it !in used }
        assertTrue(stale.isEmpty(), "allow-list entries that no longer match (remove them): $stale")
    }
}
