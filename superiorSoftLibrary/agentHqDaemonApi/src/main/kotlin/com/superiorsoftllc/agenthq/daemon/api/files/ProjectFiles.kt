package com.superiorsoftllc.agenthq.daemon.api.files

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One regular file in a [ProjectFiles] folder: its name, size and last-modified time (epoch millis). */
data class ProjectFileEntry(val name: String, val size: Long, val modifiedMillis: Long)

/**
 * DP-10a (DaemonApi 1.21.0, additive): a project-scoped file surface, rooted at `<project directory>/.agenthq/`. Every path is
 * relative to that root and `/`-separated; an absolute path, a drive letter, an empty or `.` / `..` segment, or a path that
 * resolves (through a link) outside the root is refused with [IllegalArgumentException] BEFORE anything touches the disk. Official /
 * core tier, or a community module with the grant `files`. Nothing outside `.agenthq/` is reachable.
 */
interface ProjectFiles {
    /** Writes [bytes] at [path] atomically (parents created); [modifiedMillis] sets the file's last-modified time when given. */
    fun put(path: String, bytes: ByteArray, modifiedMillis: Long? = null)

    /** The bytes at [path], or null when there is no regular file. */
    fun get(path: String): ByteArray?

    /** Size and last-modified time of the regular file at [path], or null. */
    fun stat(path: String): ProjectFileEntry?

    /** The regular files directly in folder [path] (no recursion, no links followed); empty when it does not exist. */
    fun list(path: String): List<ProjectFileEntry>

    /** Deletes the regular file at [path]; false when it was not there or could not be deleted. */
    fun delete(path: String): Boolean

    /** Sets the last-modified time of the regular file at [path]; false when it is not there. */
    fun touch(path: String, modifiedMillis: Long): Boolean

    /** The absolute, normalised path of [path] on the daemon's machine (what an agent is given). The file need not exist. */
    fun absolutePath(path: String): String

    companion object {
        /** The folder the surface is rooted at, relative to the project directory. */
        const val ROOT = ".agenthq"

        /** Where uploads live, relative to the project directory (chat, ticket and file purposes below it). */
        const val UPLOADS_REL = "$ROOT/uploads"
    }
}

/** The default of a daemon with no file surface: every call refuses. */
object NoProjectFiles : ProjectFiles {
    private fun no(): Nothing = throw IllegalStateException("this daemon offers no project files")
    override fun put(path: String, bytes: ByteArray, modifiedMillis: Long?) = no()
    override fun get(path: String): ByteArray? = no()
    override fun stat(path: String): ProjectFileEntry? = no()
    override fun list(path: String): List<ProjectFileEntry> = no()
    override fun delete(path: String): Boolean = no()
    override fun touch(path: String, modifiedMillis: Long): Boolean = no()
    override fun absolutePath(path: String): String = no()
}

/** The java.io binding of [ProjectFiles] over [projectDirectory]; the host hands this to plugins, tests build it over a temp dir. */
class DirectoryProjectFiles(projectDirectory: File) : ProjectFiles {
    private val base = projectDirectory.absoluteFile.normalize()
    private val root = File(base, ProjectFiles.ROOT)

    /** The file for [path] under the root; refuses every escape. */
    private fun resolve(path: String, allowRoot: Boolean = false): File {
        val text = path.trim()
        if (text.isEmpty()) {
            require(allowRoot) { "file path is empty" }
            return root
        }
        require(!text.startsWith("/") && !text.startsWith("\\")) { "file path '${text.take(80)}' must be relative to .agenthq/" }
        require(!(text.length >= 2 && text[1] == ':')) { "file path '${text.take(80)}' must be relative to .agenthq/" }
        val parts = text.split('/', '\\')
        require(parts.none { it.isEmpty() || it == "." || it == ".." || '\u0000' in it }) {
            "file path '${text.take(80)}' may not contain empty, '.' or '..' segments"
        }
        val file = File(root, parts.joinToString("/"))
        // A link inside .agenthq must not lead out of it: the nearest existing ancestor's real path stays under the real root.
        if (root.exists()) {
            var probe: File? = file
            while (probe != null && !probe.exists()) probe = probe.parentFile
            if (probe != null) require(probe.toPath().toRealPath().startsWith(root.toPath().toRealPath())) { "file path '${text.take(80)}' leaves .agenthq/" }
        }
        return file
    }

    override fun put(path: String, bytes: ByteArray, modifiedMillis: Long?) {
        val target = resolve(path)
        val dir = target.parentFile
        Files.createDirectories(dir.toPath())
        val temp = Files.createTempFile(dir.toPath(), ".put-", ".part")
        try {
            Files.write(temp, bytes)
            try {
                Files.move(temp, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        if (modifiedMillis != null) target.setLastModified(modifiedMillis)
    }

    override fun get(path: String): ByteArray? = resolve(path).takeIf { it.isFile }?.readBytes()

    override fun stat(path: String): ProjectFileEntry? =
        resolve(path).takeIf { it.isFile }?.let { ProjectFileEntry(it.name, it.length(), it.lastModified()) }

    override fun list(path: String): List<ProjectFileEntry> =
        resolve(path, allowRoot = true).listFiles().orEmpty()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .map { ProjectFileEntry(it.name, it.length(), it.lastModified()) }
            .sortedBy { it.name }

    override fun delete(path: String): Boolean = resolve(path).takeIf { it.isFile }?.delete() ?: false

    override fun touch(path: String, modifiedMillis: Long): Boolean = resolve(path).takeIf { it.isFile }?.setLastModified(modifiedMillis) ?: false

    override fun absolutePath(path: String): String = resolve(path).absoluteFile.normalize().path
}
