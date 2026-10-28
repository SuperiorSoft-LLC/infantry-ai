package com.superiorsoftllc.agenthq.ui.plugin

import com.superiorsoftllc.agenthq.plugin.api.EndpointAccess
import com.superiorsoftllc.agenthq.plugin.api.EndpointDeclaration
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
 * are stripped from the plugin's headers. PLUGIN-ENDPOINTS-2 (D3): for `auth` other than `none` the host reads the field(s) of the
 * secret `plugin.<module>` through [secret] (`plugin_secret_get`, owner connection only) and adds the credential to the request
 * itself. The value lives only in this object (a per-endpoint cache); it is never returned to the plugin, logged or stored.
 */
class JvmEndpointAccess(
    private val manifest: ModuleManifest,
    private val tier: Tier,
    private val config: ModulesConfig,
    private val demoLock: Boolean,
    private val log: (String) -> Unit = {},
    /** Field [field] of `plugin.<module>`: success(null) = not set, failure = refused (no owner connection, demo lock). */
    private val secret: suspend (module: String, field: String) -> Result<String?> = { m, f -> DaemonLink.window.pluginSecret?.invoke(m, f) ?: Result.failure(IllegalStateException("no window")) },
) : EndpointAccess {
    private val credentials = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The header the endpoint's `auth` adds, or a refusal reason. */
    private suspend fun credential(decl: EndpointDeclaration): Pair<Pair<String, String>?, String?> {
        val auth = decl.auth
        if (auth.kind == "none") return null to null
        suspend fun field(name: String): Pair<String?, String?> {
            credentials[name]?.let { return it to null }
            val r = secret(manifest.name, name)
            val v = r.getOrElse { return null to "endpoint '${decl.id}': credential needs the owner connection" }
                ?: return null to "endpoint '${decl.id}': field '$name' of secret 'plugin.${manifest.name.lowercase()}' is not set"
            credentials[name] = v
            return v to null
        }
        return when (auth.kind) {
            "basic" -> {
                val (u, ue) = field("user"); if (u == null) return null to ue
                val (p, pe) = field("password"); if (p == null) return null to pe
                ("Authorization" to "Basic " + java.util.Base64.getEncoder().encodeToString("$u:$p".toByteArray())) to null
            }
            else -> {
                val (v, e) = field(auth.field ?: "apiKey"); if (v == null) return null to e
                (if (auth.kind == "header") auth.name!! to v else "Authorization" to "Bearer $v") to null
            }
        }
    }

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
        val (auth, why) = credential(decl)
        if (why != null) { log("[plugins] ${manifest.name}: $why"); return EndpointReply.Refused(why) }
        return try {
            withContext(Dispatchers.IO) {
                val req = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(30))
                EndpointGate.stripHeaders(headers).forEach { (k, v) -> req.header(k, v) }
                auth?.let { (k, v) -> req.setHeader(k, v) }
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
