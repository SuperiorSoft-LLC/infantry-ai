package com.superiorsoftllc.infantry.update

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * SERVER-UPDATE-1: the install folder of a headless server archive, laid out so an update never overwrites a running
 * install (on Windows its jars are locked):
 *
 *     <root>/versions/<x.y.z>/   one unpacked archive per version
 *     <root>/current             text file: the version the launcher starts
 *     <root>/previous            text file: the version before it, for [rollback]
 *
 * The data dir is elsewhere and is never touched. [stage] unpacks a verified archive and switches `current`; the
 * restart is the launcher's job (a later slice). At most [keep] versions are kept, never `current` or `previous`.
 */
class ServerInstall(val root: File, private val keep: Int = 2) {
    private val versions = File(root, "versions")
    private val currentFile = File(root, "current")
    private val previousFile = File(root, "previous")

    fun current(): String? = read(currentFile)
    fun previous(): String? = read(previousFile)
    fun dirOf(version: String): File = File(versions, version)

    /** Unpack [verified]'s archive, check it looks like a server, and make it `current`. Returns the version dir. */
    fun stage(verified: VerifiedInstaller): File {
        val version = verified.update.latest.toString()
        val dir = dirOf(version)
        if (!dir.isDirectory) unpackInto(verified.file, dir)
        activate(version)
        prune()
        return dir
    }

    /**
     * First run from a freshly unpacked archive: copy [appHome] (which stays where it is) to `versions/<version>`, make it
     * current, and put the stable launcher stubs (`infantry`, `infantry.cmd`, which only read `current`) in [root].
     */
    fun adopt(appHome: File, version: String): File {
        val dir = dirOf(version)
        if (!dir.isDirectory) {
            versions.mkdirs()
            val scratch = Files.createTempDirectory(versions.toPath(), ".adopt-").toFile()
            try {
                val target = File(scratch, "app")
                val from = appHome.toPath()
                Files.walk(from).use { paths ->
                    paths.forEach { p ->
                        val to = target.toPath().resolve(from.relativize(p).toString())
                        if (Files.isDirectory(p)) Files.createDirectories(to) else Files.copy(p, to, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                }
                requireServerLayout(target)
                Files.move(target.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                scratch.deleteRecursively()
            }
        }
        activate(version)
        for (stub in listOf("infantry", "infantry.cmd")) {
            val src = File(dir, "bin/$stub").takeIf { it.isFile } ?: continue
            val tmp = File(root, "$stub.tmp")
            Files.copy(src.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            Files.move(tmp.toPath(), File(root, stub).toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return dir
    }

    /** Switch `current` back to `previous`. Returns the version now current, or null when there is nothing to go back to. */
    fun rollback(): String? {
        val back = previous()?.takeIf { dirOf(it).isDirectory } ?: return null
        val now = current()
        write(currentFile, back)
        if (now != null && now != back) write(previousFile, now) else previousFile.delete()
        return back
    }

    private fun unpackInto(archive: File, dir: File) {
        versions.mkdirs()
        val scratch = Files.createTempDirectory(versions.toPath(), ".unpack-").toFile()
        try {
            ArchiveUnpacker.unpack(archive, scratch)
            // The archive holds one top-level folder (InfantryAI-server-<v>-<os>-<arch>/); the version dir is that folder.
            val top = scratch.listFiles()?.singleOrNull()?.takeIf { it.isDirectory } ?: scratch
            requireServerLayout(top)
            Files.move(top.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            dir.deleteRecursively()
            throw e
        } finally {
            scratch.deleteRecursively()
        }
    }

    private fun requireServerLayout(dir: File) {
        val launcher = File(dir, "bin").listFiles { f -> f.isFile && f.name.startsWith("infantry") }
        if (launcher.isNullOrEmpty() || !File(dir, "lib").isDirectory) throw IOException("the archive is not a server install (no bin/ launcher or lib/)")
    }

    private fun activate(version: String) {
        val now = current()
        if (now == version) return
        if (now != null) write(previousFile, now)
        write(currentFile, version)
    }

    private fun prune() {
        val protected = setOfNotNull(current(), previous())
        val old = versions.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && f.name !in protected }
            ?.sortedByDescending { SemVer.parse(it.name) } ?: return
        old.drop(maxOf(0, keep - protected.size)).forEach { it.deleteRecursively() }
    }

    private fun read(f: File): String? = runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun write(f: File, text: String) {
        root.mkdirs()
        val tmp = File(root, f.name + ".tmp")
        tmp.writeText(text + "\n")
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
