package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.keystore.Keystore
import com.superiorsoftllc.agenthq.ui.pair.SavedServer
import com.superiorsoftllc.agenthq.ui.pair.SavedServers
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * PAIR-1: the desktop's [SavedServers]. The list (name + URL, no secret) is `<stateDir>/saved-servers.properties`; each token is
 * sealed in the daemon keystore (SECRETS-1) under a name derived from the URL, never written to that file or logged.
 */
class KeystoreSavedServers(private val dir: File, private val keystore: Keystore = Keystore(dir)) : SavedServers {
    private class Entry(val name: String, val url: String)

    private val file = File(dir, FILE_NAME)

    @Synchronized
    override fun list(): List<SavedServer> = read().map { SavedServer(it.name, it.url, keystore.get(secretName(it.url))) }

    @Synchronized
    override fun save(server: SavedServer) {
        val token = server.token?.trim()?.takeIf { it.isNotEmpty() }
        // The token first: a failure to seal it must not leave a server listed without its credential.
        if (token != null) keystore.set(secretName(server.url), token) else keystore.clear(secretName(server.url))
        write(read().filter { it.url != server.url } + Entry(server.name, server.url))
    }

    @Synchronized
    override fun remove(url: String) {
        keystore.clear(secretName(url))
        write(read().filter { it.url != url })
    }

    private fun read(): List<Entry> {
        if (!file.isFile) return emptyList()
        val props = runCatching { Properties().also { p -> file.reader(Charsets.UTF_8).use(p::load) } }.getOrNull() ?: return emptyList()
        return generateSequence(0) { it + 1 }.map { i -> props.getProperty("$i.url")?.let { Entry(props.getProperty("$i.name") ?: it, it) } }
            .takeWhile { it != null }.filterNotNull().toList()
    }

    private fun write(entries: List<Entry>) {
        dir.mkdirs()
        val props = Properties()
        entries.forEachIndexed { i, e -> props.setProperty("$i.name", e.name); props.setProperty("$i.url", e.url) }
        file.writer(Charsets.UTF_8).use { props.store(it, null) }
    }

    companion object {
        const val FILE_NAME = "saved-servers.properties"

        /** A keystore-legal name for [url]'s token: the URL's hash, so the name reveals nothing. */
        fun secretName(url: String): String =
            "server-token-" + MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
    }
}
