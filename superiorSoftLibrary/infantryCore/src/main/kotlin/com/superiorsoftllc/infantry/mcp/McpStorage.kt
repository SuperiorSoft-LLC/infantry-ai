// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP-REGISTRY-1: where the MCP registry keeps its own data (the Infantry-owned servers, the grants): named JSON documents.
 * [FileMcpStorage] is the daemon's (files in the state dir); [MemoryMcpStorage] is for tests. Values are never secrets: a server
 * names Secrets entries instead (see [McpOwnedServers]).
 */
interface McpStorage {
    /** The document [name], or null when it was never written. */
    fun read(name: String): String?

    /** Replace the document [name] with [text], all at once (a reader sees the old text or the new, never half). */
    fun write(name: String, text: String)
}

/** [McpStorage] as files under [dir] (the daemon passes `<stateDir>/mcp`): written to a temp file and moved over, owner-only. */
class FileMcpStorage(private val dir: File) : McpStorage {
    private val lock = Any()

    override fun read(name: String): String? = synchronized(lock) {
        fileOf(name).takeIf { it.isFile }?.readText()
    }

    override fun write(name: String, text: String) = synchronized(lock) {
        dir.mkdirs()
        val target = fileOf(name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        McpFiles.ownerOnly(tmp)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }

    private fun fileOf(name: String): File {
        require(McpFiles.SAFE_NAME.matches(name)) { "bad MCP document name '$name'" }
        return File(dir, "$name.json")
    }
}

/** [McpStorage] in memory (tests). */
class MemoryMcpStorage : McpStorage {
    private val docs = ConcurrentHashMap<String, String>()
    override fun read(name: String): String? = docs[name]
    override fun write(name: String, text: String) {
        docs[name] = text
    }
}

internal object McpFiles {
    /** A document, session or file name: letters, digits, `.`, `_`, `-`; never a path. */
    val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    /** Read and write for the owner only (POSIX); on a file system without POSIX permissions, the closest the JDK offers. */
    fun ownerOnly(f: File) {
        val posix = runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString(if (f.isDirectory) "rwx------" else "rw-------")) }
        if (posix.isFailure) {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
            if (f.isDirectory) { f.setExecutable(false, false); f.setExecutable(true, true) }
        }
    }
}
