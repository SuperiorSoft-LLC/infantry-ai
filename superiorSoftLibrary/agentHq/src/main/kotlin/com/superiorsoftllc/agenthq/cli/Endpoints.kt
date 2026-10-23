package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.session.stateDir
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One daemon a client knows by name. The token itself is NEVER stored here: [tokenFile] names the file
 * that holds it, so an endpoint list can be shared, pasted or committed without exposing a secret.
 */
@Serializable
data class Endpoint(
    val name: String,
    val url: String,
    val tokenFile: String? = null,
)

@Serializable
private data class EndpointFile(val endpoints: List<Endpoint> = emptyList())

/**
 * The named-daemon list that lets ONE client talk to SEVERAL daemons, each with its own token.
 *
 * Each command still talks to exactly one daemon: `--endpoint NAME` selects it. A client that needs two
 * daemons at once runs two commands. Fanning one command out across daemons is deliberately not built.
 */
internal object Endpoints {
    private val json = Json { ignoreUnknownKeys = true }

    /** `--endpoints FILE`, else `AGENTHQ_ENDPOINTS`, else `endpoints.json` in the state directory. */
    fun defaultFile(getenv: (String) -> String?): File =
        getenv("AGENTHQ_ENDPOINTS")?.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(stateDir(getenv), "endpoints.json")

    /** A missing file means no endpoints. A malformed one is an error that names the file, never its contents. */
    fun load(file: File): List<Endpoint> {
        if (!file.exists()) return emptyList()
        val parsed = try {
            json.decodeFromString(EndpointFile.serializer(), file.readText(Charsets.UTF_8).removePrefix(Char(0xFEFF).toString()))
        } catch (e: Exception) {
            throw IllegalArgumentException("cannot read endpoints file ${file.path}: not valid endpoint JSON")
        }
        val names = parsed.endpoints.map { it.name }
        require(names.all { it.isNotBlank() }) { "endpoints file ${file.path} has an endpoint with no name" }
        require(names.size == names.toSet().size) { "endpoints file ${file.path} names two endpoints the same" }
        parsed.endpoints.forEach { endpoint ->
            require(endpoint.url.startsWith("ws://") || endpoint.url.startsWith("wss://")) {
                "endpoint '${endpoint.name}' in ${file.path} must be a ws:// or wss:// URL"
            }
        }
        return parsed.endpoints
    }
}
