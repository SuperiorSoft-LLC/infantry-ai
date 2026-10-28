package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.EndpointAccess
import com.superiorsoftllc.agenthq.plugin.api.EndpointReply
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.ModulesConfig
import com.superiorsoftllc.agenthq.plugin.api.Tier
import com.superiorsoftllc.agenthq.plugin.host.EndpointGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * PLUGIN-ENDPOINTS-1 (D2): ONE module's backend access. Its own [HttpClient]: no cookie handler, no default headers, redirects
 * never followed, 30 s timeout, replies cut at 4 MiB. [EndpointGate] decides first; Authorization/Cookie/Host/Proxy-*\/X-AgentHQ-*
 * are stripped from the plugin's headers. (The credential of D3 is not wired yet: `auth` is declared and validated only.)
 */
class JvmEndpointAccess(
    private val manifest: ModuleManifest,
    private val tier: Tier,
    private val config: ModulesConfig,
    private val demoLock: Boolean,
    private val log: (String) -> Unit = {},
) : EndpointAccess {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(30)).build()
    }

    override suspend fun call(endpoint: String, method: String, path: String, body: String?, headers: Map<String, String>): EndpointReply {
        EndpointGate.refusal(endpoint, method, path, manifest, tier, config, demoLock)?.let {
            log("[plugins] ${manifest.name}: $it")
            return EndpointReply.Refused(it)
        }
        val decl = manifest.endpoints.first { it.id == endpoint }
        val origin = EndpointGate.origin(decl, manifest.name, config) ?: return EndpointReply.Refused("endpoint '$endpoint': url is not valid")
        return try {
            withContext(Dispatchers.IO) {
                val req = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(30))
                EndpointGate.stripHeaders(headers).forEach { (k, v) -> req.header(k, v) }
                req.method(method.uppercase(), if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
                val reply = client.send(req.build(), HttpResponse.BodyHandlers.ofInputStream())
                val bytes = reply.body().use { it.readNBytes(MAX_REPLY) }
                EndpointReply.Ok(reply.statusCode(), bytes.decodeToString(), reply.headers().map().mapValues { it.value.joinToString(",") })
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            EndpointReply.Failed("could not reach endpoint '$endpoint' (${e::class.simpleName ?: "error"})")
        }
    }

    private companion object { const val MAX_REPLY = 4 * 1024 * 1024 }
}
