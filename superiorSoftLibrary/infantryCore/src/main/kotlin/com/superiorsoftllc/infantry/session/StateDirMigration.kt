package com.superiorsoftllc.infantry.session

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * RENAME-2: the state dir moved from the old product name to `Infantry`. On first start, if only the old directory
 * exists it is COPIED to the new one (via a temp sibling that is renamed into place, so a crash never leaves a half
 * state) and the new one is used; if both exist the new one is used; the old one is never deleted or changed.
 * Names containing the old product name (`agenthq.db`, `agenthq-token`, ...) get the new name in the copy.
 */
object StateDirMigration {
    private val LEGACY_WORD = Regex("agenthq", RegexOption.IGNORE_CASE)

    enum class Outcome { NOTHING_TO_DO, MIGRATED, USED_NEW, FAILED }

    /** [name] with the old product name replaced (`agenthq.db` -> `infantry.db`, `AgentHQ` -> `Infantry`). */
    fun newName(name: String): String = LEGACY_WORD.replace(name) { m -> if (m.value[0].isUpperCase()) "Infantry" else "infantry" }

    /** Files that belong to a running process and must not be copied. */
    private val SKIP = setOf("state.lock", "daemon.lock", "daemon.pid", "daemon.port")

    fun migrate(old: File, new: File, log: (String) -> Unit = { System.err.println(it) }): Outcome {
        if (old.absoluteFile.normalize() == new.absoluteFile.normalize()) return upgradeLayout(new, log)
        if (new.exists()) return Outcome.USED_NEW
        if (!old.isDirectory) return Outcome.NOTHING_TO_DO
        val temp = File(new.absoluteFile.parentFile, new.name + ".migrating")
        return try {
            temp.deleteRecursively()
            copyTree(old, temp)
            new.absoluteFile.parentFile.mkdirs()
            Files.move(temp.toPath(), new.toPath(), StandardCopyOption.ATOMIC_MOVE)
            log("[infantry] migrated the state dir ${old.path} -> ${new.path} (the old one is left untouched)")
            Outcome.MIGRATED
        } catch (e: Exception) {
            temp.deleteRecursively()
            log("[infantry] state dir migration ${old.path} -> ${new.path} failed: ${e.message}")
            Outcome.FAILED
        }
    }

    /**
     * In one directory that already exists (an explicit state dir in the old layout): every top-level entry whose name
     * carries the old product name is copied to its new name unless that exists; the old entry stays.
     */
    fun upgradeLayout(dir: File, log: (String) -> Unit = { System.err.println(it) }): Outcome {
        val entries = dir.listFiles()?.filter { LEGACY_WORD.containsMatchIn(it.name) && it.name !in SKIP } ?: return Outcome.NOTHING_TO_DO
        var did = false
        for (e in entries) {
            val target = File(dir, newName(e.name))
            if (target.exists()) continue
            try { copyTree(e, target); did = true } catch (x: Exception) { log("[infantry] could not copy ${e.path} -> ${target.path}: ${x.message}"); return Outcome.FAILED }
        }
        if (did) log("[infantry] upgraded the old file names in ${dir.path} (the old files are left untouched)")
        return if (did) Outcome.MIGRATED else Outcome.NOTHING_TO_DO
    }

    private fun copyTree(from: File, to: File) {
        if (from.isDirectory) {
            to.mkdirs()
            for (c in from.listFiles().orEmpty()) {
                if (from.parentFile != null && c.name in SKIP) continue
                copyTree(c, File(to, if (from.isDirectory) newName(c.name) else c.name))
            }
        } else {
            to.parentFile?.mkdirs()
            Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
