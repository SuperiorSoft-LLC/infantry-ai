// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * MCP-REGISTRY-1: a session's MCP config files live in the state dir ([root] = `<stateDir>/mcp/sessions`), never in the worktree and
 * never in `java.io.tmpdir`: one owner-only folder per session, deleted with the session ([delete]). The resolved specs carry secret
 * values, so the files are written owner-only and their content is never logged. A harness decides the format; [standardJson] is the
 * common `{"mcpServers": {...}}` shape most MCP clients read.
 */
class McpConfigFiles(private val root: File) {

    /** Write [content] as [fileName] in [sessionId]'s folder (owner-only) and return the file. */
    fun write(sessionId: String, fileName: String, content: String): File {
        require(McpFiles.SAFE_NAME.matches(fileName)) { "bad MCP config file name '$fileName'" }
        val dir = dirOf(sessionId)
        dir.mkdirs()
        McpFiles.ownerOnly(dir)
        val file = File(dir, fileName)
        val tmp = File(dir, "$fileName.tmp")
        tmp.writeText(content)
        McpFiles.ownerOnly(tmp)
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        return file
    }

    /** [sessionId]'s folder, whether or not it exists. */
    fun dirOf(sessionId: String): File {
        require(McpFiles.SAFE_NAME.matches(sessionId)) { "bad session id '$sessionId'" }
        return File(root, sessionId)
    }

    /** Delete [sessionId]'s folder and every file in it; false when there was none. */
    fun delete(sessionId: String): Boolean = dirOf(sessionId).let { it.exists() && it.deleteRecursively() }

    /** Delete the folder of every session [keep] does not name (a daemon start sweeps what a crash left behind); the count removed. */
    fun sweep(keep: Set<String>): Int = root.listFiles().orEmpty().count { it.isDirectory && it.name !in keep && it.deleteRecursively() }

    companion object {
        /**
         * [specs] as `{"mcpServers": {name: {type, command, args, env, cwd} | {type, url, headers}}}`. Contains secret values: write it
         * with [write], never log it.
         */
        fun standardJson(specs: List<McpServerSpec>): String = JsonObject(mapOf("mcpServers" to JsonObject(specs.associate { s ->
            s.name to when (val t = s.transport) {
                is McpTransport.Stdio -> JsonObject(buildMap {
                    put("type", JsonPrimitive("stdio"))
                    put("command", JsonPrimitive(t.command.first()))
                    put("args", JsonArray(t.command.drop(1).map(::JsonPrimitive)))
                    if (t.env.isNotEmpty()) put("env", JsonObject(t.env.mapValues { JsonPrimitive(it.value) }))
                    t.workingDir?.let { put("cwd", JsonPrimitive(it)) }
                })
                is McpTransport.Http -> JsonObject(buildMap {
                    put("type", JsonPrimitive("http"))
                    put("url", JsonPrimitive(t.url))
                    if (t.headers.isNotEmpty()) put("headers", JsonObject(t.headers.mapValues { JsonPrimitive(it.value) }))
                })
            }
        }))).toString()
    }
}
