package com.superiorsoftllc.infantry.ui.net

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** HARNESS-PLUGIN-4 H5a (1.145.0): the part of a `GET /plugins` module this client reads. */
@Serializable
data class DaemonModuleWire(val name: String, val harness: String? = null, val harnessIds: List<String> = emptyList())

@Serializable
data class DaemonModulesWire(val modules: List<DaemonModuleWire> = emptyList())

/** H5a: the daemon's harness state per module (`GET /plugins` `harness`); null = it could not be read (offline, refused, older daemon). */
class HttpPluginsApi(
    wsUrl: String,
    private val token: String?,
    private val http: HttpClient = createPlatformHttpClient(),
) {
    private val base: String = HttpServerUpdateApi.httpBase(wsUrl)

    suspend fun harnessStatuses(): Map<String, String>? = try {
        val response = http.get("$base/plugins") { token?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
        if (response.status.value !in 200..299) null else parse(response.bodyAsText())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** module name -> its harness state, for the modules that have a harness. */
        fun parse(body: String): Map<String, String> =
            json.decodeFromString(DaemonModulesWire.serializer(), body).modules.mapNotNull { m -> m.harness?.let { m.name to it } }.toMap()

        const val SINCE_MAJOR = 1
        const val SINCE_MINOR = 145
    }
}
