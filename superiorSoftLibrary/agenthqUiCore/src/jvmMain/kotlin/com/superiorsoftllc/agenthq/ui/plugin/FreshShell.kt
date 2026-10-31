package com.superiorsoftllc.agenthq.ui.plugin

import java.io.File
import java.lang.management.ManagementFactory

/**
 * NS10 (docs/NORTH-STAR-DESIGN.md 3d step 3): "Try in a fresh shell". After an export, start a REAL second process: the
 * shell entry (`AppMainKt`) with `--app <appDir>`, on this JVM's own runtime and classpath minus the app UI and the board
 * module (the shell-only classpath, as `runShell` filters it). Same launcher in dev and installed: the installed image
 * keeps `bin/java` (PKG-4), so `java.home` works from the jpackage launcher too.
 *
 * The app dir is `<stateDir>/fresh-shells/<appId>`: `app.json` with a temp appId (so the child's bundled daemon gets its
 * own `~/.infantry/apps/<appId>` state, NS5, never this install's), an empty `plugins/` root, the exported `.ihq` beside
 * it for the owner to drop, and `shell.log` (the child's stdout/stderr; javaw has no console, issue I2).
 */
object FreshShell {
    const val MAIN_CLASS = "com.superiorsoftllc.agenthq.ui.AppMainKt"

    /** Same filter as build.gradle.kts `isShellJar`: the fresh shell must not have the exported section compiled in. */
    const val EXCLUDE_PROPERTY = "agenthq.freshShell.exclude"

    /** Path fragments of the exported section's own jars, handed in by the app build (core names no concrete plugin). */
    fun excluded(property: (String) -> String? = System::getProperty): List<String> =
        property(EXCLUDE_PROPERTY)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun isShellEntry(path: String, excluded: List<String> = excluded()): Boolean = path.replace('\\', '/').let { p -> excluded.none { it in p } }

    /** Writes the fresh app dir for [exported] (composition [name]) and returns it. */
    fun prepare(stateDir: File, exported: File, name: String, now: Long = System.currentTimeMillis()): File {
        val appId = "try.${name.lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "section" }}.$now"
        val dir = File(File(stateDir, "fresh-shells"), appId)
        File(dir, "plugins/core").mkdirs()
        File(dir, "plugins/community").mkdirs()
        File(dir, "app.json").writeText(
            "{ \"appId\": \"$appId\", \"name\": \"$name (fresh shell)\", \"windowTitle\": \"$name (fresh shell)\",\n" +
                "  \"daemon\": { \"mode\": \"connect-or-bundle\", \"url\": null, \"seedProject\": \"$name\" } }\n",
        )
        exported.copyTo(File(dir, exported.name), overwrite = true)
        return dir
    }

    /** Pure: the child's command line. [jvmArgs] keeps only the heap caps (PERF-2) and the version/build stamps. */
    fun command(javaHome: String, classPath: String, jvmArgs: List<String>, appDir: File, windows: Boolean, exclude: List<String> = excluded()): List<String> {
        val bin = File(javaHome, "bin")
        val java = if (windows) File(bin, "javaw.exe").takeIf { it.isFile } ?: File(bin, "java.exe") else File(bin, "java")
        val cp = classPath.split(File.pathSeparatorChar).filter { it.isNotBlank() && isShellEntry(it, exclude) }.joinToString(File.pathSeparator)
        val kept = jvmArgs.filter { a -> a.startsWith("-Xm") || a.startsWith("-Xss") || a.startsWith("-XX:") || a.startsWith("-Dagenthq.version=") || a.startsWith("-Dagenthq.build=") }
        return listOf(java.path) + kept + listOf("-cp", cp, MAIN_CLASS, "--app", appDir.absolutePath)
    }

    /** Prepares the app dir and starts the child; returns the line the Registered view shows. */
    fun launch(stateDir: File, exported: File, name: String): String {
        val dir = prepare(stateDir, exported, name)
        val cmd = command(
            System.getProperty("java.home"), System.getProperty("java.class.path").orEmpty(),
            ManagementFactory.getRuntimeMXBean().inputArguments, dir, System.getProperty("os.name").startsWith("Windows"),
        )
        val log = File(dir, "shell.log")
        ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
        return "Fresh shell started on $dir: drop ${exported.name} (in that folder) on its drop box"
    }
}
