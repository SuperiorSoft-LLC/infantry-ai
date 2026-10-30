package com.superiorsoftllc.agenthq.daemon.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.Base64

/**
 * RUNTIME-LOAD-4 (DaemonApi 1.4.0, wire contract 1.147.0, additive): a tool argument that names files on the CALLER's machine.
 *
 * A daemon tool runs in the daemon, which may be another machine than the agent's (location transparency), so it can never open
 * the agent's paths itself. A tool that takes local files lists those argument keys in [DaemonTool.localFiles]; the daemon
 * advertises them as `localFiles` in its `plugin_tools` descriptor, and the client that holds the files (`agenthq mcp serve`, which
 * runs where the agent runs) replaces each element of those arrays with what [encode] makes of it before it sends the call:
 *  - a path that is a regular file of at most [MAX_BYTES]: `{"path": <as given>, "size": n, "base64": <the bytes>}`;
 *  - a larger regular file: `{"path", "size"}` (no bytes: the tool refuses it by its size);
 *  - anything else (no such file, a directory, a name the tool resolves itself): the string as given.
 * The tool reads each element back with [decode]. Nothing else changes: other arguments pass untouched, and a client that knows
 * nothing of this sends plain strings, which a tool reads as files it cannot see ([LocalFile.size] null).
 */
data class LocalFile(
    /** The path exactly as the caller gave it. */
    val path: String,
    /** The file's size in bytes; null = the caller has no regular file there. */
    val size: Long? = null,
    /** The file's bytes; null when there is no file, or it is over [LocalFiles.MAX_BYTES]. */
    val bytes: ByteArray? = null,
) {
    val exists: Boolean get() = size != null
    override fun equals(other: Any?) = other is LocalFile && other.path == path && other.size == size && (other.bytes?.contentEquals(bytes ?: ByteArray(0)) ?: (bytes == null))
    override fun hashCode() = path.hashCode() * 31 + (size?.hashCode() ?: 0)
}

object LocalFiles {
    /** The most bytes one file carries inline (the image upload limit); a larger file is sent by its size only. */
    const val MAX_BYTES: Long = 10L * 1024 * 1024

    /** The `plugin_tools` descriptor key naming a tool's local-file arguments (a client strips it from MCP `tools/list`). */
    const val DESCRIPTOR_KEY = "localFiles"

    /** Client side: [path] as the caller's machine has it, read from [resolve] (default: relative to the process's own directory). */
    fun encode(path: String, resolve: (String) -> File = { File(it.trim()) }): JsonElement {
        val file = runCatching { resolve(path) }.getOrNull()?.takeIf { it.isFile } ?: return JsonPrimitive(path)
        val size = file.length()
        return JsonObject(buildMap {
            put("path", JsonPrimitive(path))
            put("size", JsonPrimitive(size))
            if (size <= MAX_BYTES) put("base64", JsonPrimitive(Base64.getEncoder().encodeToString(file.readBytes())))
        })
    }

    /** Client side: the call's [arguments] with every element of the [keys] arrays encoded; a key that is not an array of strings is left as it is. */
    fun encodeArguments(arguments: JsonObject, keys: Collection<String>, resolve: (String) -> File = { File(it.trim()) }): JsonObject =
        if (keys.isEmpty()) arguments else JsonObject(arguments.mapValues { (key, value) ->
            if (key !in keys || value !is JsonArray) value
            else JsonArray(value.map { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.let { encode(it.content, resolve) } ?: item })
        })

    /** Tool side: one element of a local-file array, or null when it is neither a string nor an encoded file. */
    fun decode(element: JsonElement): LocalFile? = when (element) {
        is JsonPrimitive -> element.takeIf { it.isString }?.let { LocalFile(it.content) }
        is JsonObject -> {
            val path = (element["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            path?.let {
                val size = (element["size"] as? JsonPrimitive)?.longOrNull
                val bytes = (element["base64"] as? JsonPrimitive)?.contentOrNull?.let { b -> runCatching { Base64.getDecoder().decode(b) }.getOrNull() }
                LocalFile(it, size, bytes)
            }
        }
        else -> null
    }
}
