package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.EndpointDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ModulesConfig
import com.superiorsoftllc.infantry.plugin.api.Tier

/**
 * PLUGIN-ENDPOINTS-1 (docs/PLUGIN-ENDPOINTS-DESIGN.md D1, D5): the pure rule every platform shares for a module's backend call.
 * Redirects are never followed (the transport's duty).
 */
object EndpointGate {
    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
    private val AUTH = setOf("none", "bearer", "header", "basic")
    private val DEV_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
    private val URL = Regex("""^(https?)://([^/:*?#\s]+)(:\d+)?(/[^?#\s]*)?$""")

    /** Headers the host removes from a plugin's request (matched case-insensitively; `proxy-` and `x-infantry-` are prefixes). */
    fun stripHeaders(headers: Map<String, String>): Map<String, String> = headers.filterKeys {
        val k = it.lowercase()
        k !in setOf("authorization", "cookie", "host") && !k.startsWith("proxy-") && !k.startsWith("x-infantry-")
    }

    /** Why [declared] cannot be loaded (checked like `platforms`), or null. */
    fun invalid(declared: List<EndpointDeclaration>): String? {
        declared.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }?.let { return "endpoint '${it.key}' is declared twice" }
        for (e in declared) {
            val bad = urlProblem(e.url) ?: when {
                e.id.isBlank() -> "an endpoint has no id"
                e.methods.isEmpty() || e.methods.any { it !in METHODS } -> "methods must be from ${METHODS.joinToString()}"
                e.auth.kind !in AUTH -> "unknown auth kind '${e.auth.kind}' (known: ${AUTH.joinToString()})"
                e.auth.kind == "header" && e.auth.name.isNullOrBlank() -> "auth header needs a name"
                else -> null
            }
            if (bad != null) return "endpoint '${e.id}': $bad"
        }
        return null
    }

    private fun urlProblem(url: String): String? {
        val m = URL.matchEntire(url) ?: return "url '$url' is not scheme://host[:port]/path"
        val (scheme, host) = m.groupValues[1] to m.groupValues[2]
        return if (scheme == "http" && host !in DEV_HOSTS) "http is only allowed for ${DEV_HOSTS.joinToString()}" else null
    }

    /** The url in force: the owner's override in modules.json, else the manifest's. */
    fun effectiveUrl(e: EndpointDeclaration, module: String, config: ModulesConfig): String =
        config.setting(module).endpoints[e.id]?.url?.takeIf { it.isNotBlank() } ?: e.url

    /** The request path (with an optional `?query`) with percent-escapes decoded once, or null if it is not acceptable. */
    private fun normalised(path: String): String? {
        val p = path.substringBefore('?')
        if (!p.startsWith("/")) return null
        val decoded = percentDecode(p) ?: return null
        return decoded.takeIf { "//" !in it && it.indexOf(92.toChar()) < 0 && it.split('/').none { s -> s == ".." || s == "." } }
    }

    private fun percentDecode(s: String): String? {
        val out = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') {
                val b = s.substring(i + 1, minOf(i + 3, s.length)).toIntOrNull(16)?.takeIf { i + 3 <= s.length } ?: return null
                out += b.toByte(); i += 3
            } else { out += s[i].toString().encodeToByteArray().toList(); i++ }
        }
        return out.toByteArray().decodeToString()
    }

    private fun matches(pattern: String, path: String): Boolean {
        val pat = pattern.trimEnd('/').split('/'); val seg = path.trimEnd('/').split('/')
        for ((i, p) in pat.withIndex()) {
            if (p == "**") return true
            if (i >= seg.size || (p != "*" && p != seg[i])) return false
        }
        return pat.size == seg.size
    }

    /** The reason the call is refused, or null. [path] is the path on the endpoint's host, e.g. `/v1/items`. */
    fun refusal(endpoint: String, method: String, path: String, manifest: ModuleManifest, tier: Tier, config: ModulesConfig, demoLock: Boolean): String? {
        val e = manifest.endpoints.firstOrNull { it.id == endpoint } ?: return "endpoint '$endpoint' is not declared in module.json"
        if (method.uppercase() !in e.methods.map { it.uppercase() }) return "endpoint '$endpoint': method ${method.uppercase()} not declared"
        val clean = normalised(path) ?: return "endpoint '$endpoint': path '$path' is not acceptable"
        val pattern = URL.matchEntire(effectiveUrl(e, manifest.name, config))?.groupValues?.get(4)?.ifEmpty { "/" }
            ?: return "endpoint '$endpoint': url is not valid"
        if (!matches(pattern, clean)) return "endpoint '$endpoint': path '$path' is outside $pattern"
        if (tier == Tier.COMMUNITY && "endpoints" !in config.setting(manifest.name).allow) return "a community module needs 'endpoints' allowed in modules.json"
        if (demoLock && !(tier == Tier.CORE && endpoint in config.demoLock.allowEndpoints)) return "endpoint '$endpoint' is not allowed under the lockdown"
        return null
    }

    /** `scheme://host[:port]` of the url in force, or null. */
    fun origin(e: EndpointDeclaration, module: String, config: ModulesConfig): String? =
        URL.matchEntire(effectiveUrl(e, module, config))?.let { it.groupValues[1] + "://" + it.groupValues[2] + it.groupValues[3] }
}
