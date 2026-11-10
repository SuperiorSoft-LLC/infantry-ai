// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.session.stateDir
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.Reader

/** CLI-LOGIN-1: the saved default connection. The token lives in its own file, never inside login.json. */
@Serializable
internal data class SavedLogin(val url: String, val tokenFile: String? = null)

internal object LoginStore {
    private const val FILE = "login.json"
    private const val TOKEN_FILE = "login.token"
    private val json = Json { ignoreUnknownKeys = true }

    fun load(getenv: (String) -> String?): SavedLogin? = runCatching {
        File(stateDir(getenv), FILE).takeIf { it.isFile }?.let { json.decodeFromString(SavedLogin.serializer(), it.readText(Charsets.UTF_8)) }
    }.getOrNull()

    fun token(login: SavedLogin): String? =
        login.tokenFile?.let { File(it) }?.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }

    fun save(getenv: (String) -> String?, url: String, token: String?) {
        val dir = stateDir(getenv).also { it.mkdirs() }
        val tf = File(dir, TOKEN_FILE)
        if (token.isNullOrBlank()) tf.delete() else tf.writeText(token.trim(), Charsets.UTF_8)
        File(dir, FILE).writeText(
            json.encodeToString(SavedLogin.serializer(), SavedLogin(url, tf.takeIf { it.isFile }?.path)), Charsets.UTF_8,
        )
    }

    /** True when something was removed. */
    fun clear(getenv: (String) -> String?): Boolean {
        val dir = stateDir(getenv)
        val a = File(dir, FILE).delete()
        val b = File(dir, TOKEN_FILE).delete()
        return a || b
    }
}

/**
 * `infantry login [--url URL] [--token TOKEN | --token-file F]`, `infantry logout`, `infantry whoami`.
 * Local commands (no daemon connection is needed to run them). This is the CLI's own daemon connection; it is unrelated
 * to Claude's `/login` (the harness account). The token is never printed.
 */
internal object LoginCli {
    const val DEFAULT_URL = "ws://127.0.0.1:8787/ws"

    fun run(
        command: String, args: List<String>, out: Appendable, err: Appendable, getenv: (String) -> String?,
        input: () -> Reader, probe: (Target) -> ReadyInfo? = { daemonReady(it, 5_000) },
        readSecret: (() -> String?)? = null,
    ): Int {
        when (command) {
            "logout" -> {
                out.appendLine(if (LoginStore.clear(getenv)) "logged out: the saved connection is removed" else "not logged in")
                return 0
            }
            "whoami" -> {
                val l = LoginStore.load(getenv)
                if (l == null) {
                    out.appendLine("not logged in (the default server applies)")
                    return 1
                }
                out.appendLine("server: ${l.url}")
                out.appendLine("token: ${if (LoginStore.token(l) != null) "saved" else "none"}")
                return 0
            }
        }
        var url: String? = null
        var token: String? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--url" -> url = args.getOrNull(++i)
                "--token" -> token = args.getOrNull(++i)
                "--token-file" -> token = args.getOrNull(++i)?.let { runCatching { File(it).readText().trim() }.getOrNull() }
                    ?: run { err.appendLine("error: --token-file cannot be read"); return 2 }
                else -> {
                    err.appendLine("error: unknown login argument '${args[i]}'")
                    err.appendLine("usage: infantry login [--url <ws://host:port>] [--token <token>|--token-file <file>]")
                    return 2
                }
            }
            i++
        }
        val reader = lazy { input().buffered() }
        val interactive = url == null
        if (url == null) {
            out.append("Server URL [$DEFAULT_URL]: ")
            url = reader.value.readLine()?.trim().orEmpty().ifEmpty { DEFAULT_URL }
        }
        if (token == null) token = getenv("INFANTRY_TOKEN")?.takeIf { it.isNotBlank() }
        if (token == null && interactive) {
            out.append("Access token (hidden, empty for none): ")
            token = (readSecret?.invoke() ?: System.console()?.readPassword()?.concatToString() ?: reader.value.readLine())?.trim()
            out.appendLine("")
        }
        val full = fullUrl(url.trim())
        if (full == null) {
            err.appendLine("error: not a valid server URL: '${url.trim()}'")
            return 2
        }
        val tok = token?.takeIf { it.isNotBlank() }
        val ready = probe(Target(full, tok, "login"))
        if (ready == null) {
            err.appendLine("error: cannot connect to $full (unreachable, or the token was refused); nothing saved")
            return 1
        }
        LoginStore.save(getenv, full, tok)
        out.appendLine("logged in: now talking to $full (contract ${ready.contract}${ready.build?.let { ", build $it" } ?: ""})")
        return 0
    }

    /** Accepts host:port, http(s):// and ws(s)://; adds /ws. Null when there is no host. */
    private fun fullUrl(raw: String): String? {
        val withScheme = when {
            raw.startsWith("ws://") || raw.startsWith("wss://") -> raw
            raw.startsWith("https://") -> "wss://" + raw.removePrefix("https://")
            raw.startsWith("http://") -> "ws://" + raw.removePrefix("http://")
            else -> "ws://$raw"
        }
        val uri = runCatching { java.net.URI.create(withScheme) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        return if (uri.path.isNullOrEmpty() || uri.path == "/") withScheme.trimEnd('/') + "/ws" else withScheme
    }
}
