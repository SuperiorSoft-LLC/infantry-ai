package com.superiorsoftllc.infantry.ui.pair

/** PAIR-1: a server a client keeps. The token is a credential: [toString] never prints it. */
class SavedServer(val name: String, val url: String, val token: String?) {
    override fun toString(): String = "SavedServer(name=$name, url=$url, token=${if (token == null) "none" else "set"})"
}

/**
 * PAIR-1: the servers a client remembers, each token sealed at rest by the platform (Android Keystore; the desktop
 * keystore). Pairing saves through it; the server switcher builds its UI on the same interface. A store with one slot
 * (Android today) keeps only the latest server.
 */
interface SavedServers {
    fun list(): List<SavedServer>

    /** Adds [server], or replaces the one with the same URL. */
    fun save(server: SavedServer)

    fun remove(url: String)
}

/** In memory: tests, and a host with nothing to persist to. */
class InMemorySavedServers : SavedServers {
    private val servers = LinkedHashMap<String, SavedServer>()
    override fun list(): List<SavedServer> = servers.values.toList()
    override fun save(server: SavedServer) {
        servers[server.url] = server
    }
    override fun remove(url: String) {
        servers.remove(url)
    }
}

/** What a scanned or pasted link does: save the server and token, then connect with them. */
fun applyPairing(link: PairingLink, store: SavedServers?, connect: (url: String, token: String?) -> Unit) {
    store?.save(SavedServer(link.name ?: PairingHosts.labelOf(link.url), link.url, link.token))
    connect(link.url, link.token)
}

object PairingHosts {
    /** `host:port` of [url], the label for a server that sent no name. */
    fun labelOf(url: String): String = url.substringAfter("://").substringBefore('/')

    /**
     * The addresses to offer for the server at [connectedUrl]: itself when other devices can reach it, else (a loopback
     * address) the same port and path on each of [localAddresses]. Tailscale (100.64/10) first, then private LAN IPv4.
     * Empty when there is none.
     */
    fun reachableUrls(connectedUrl: String, localAddresses: List<String>): List<String> {
        if (!PairingLink.isLoopbackUrl(connectedUrl)) return listOf(connectedUrl)
        val scheme = connectedUrl.substringBefore("://")
        val rest = connectedUrl.substringAfter("://")
        val hostPort = rest.substringBefore('/')
        val path = rest.removePrefix(hostPort)
        val port = if (hostPort.startsWith("[")) hostPort.substringAfter("]").removePrefix(":") else hostPort.substringAfter(':', "")
        return localAddresses.filter { !PairingLink.isLoopbackHost(it) }.distinct().sortedBy { rank(it) }
            .map { host -> "$scheme://${if (host.contains(':')) "[$host]" else host}${if (port.isEmpty()) "" else ":$port"}$path" }
    }

    private fun rank(address: String): Int {
        val p = address.split('.').mapNotNull { it.toIntOrNull() }
        return when {
            p.size != 4 -> 3
            p[0] == 100 && p[1] in 64..127 -> 0
            p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) -> 1
            p[0] == 169 && p[1] == 254 -> 4
            else -> 2
        }
    }
}
