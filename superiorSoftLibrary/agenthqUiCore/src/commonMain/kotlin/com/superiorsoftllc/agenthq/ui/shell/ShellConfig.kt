package com.superiorsoftllc.agenthq.ui.shell

/** The connection the shell opens on launch. The user can still change it in the connection dialog. */
/** SEC-FIX-1: [ownerToken] is the owner credential of the daemon at [url] only (null = connect as no owner). */
data class ShellConfig(val url: String, val token: String?, val ownerToken: String? = null)

/**
 * Same precedence the CLI uses: URL from [ShellKeys.WS_URL] else the host default; token from
 * [ShellKeys.TOKEN_FILE] (trimmed, ignored when unreadable or blank) else [ShellKeys.TOKEN]. Blank means unset.
 */
fun resolveShellConfig(host: PlatformHost): ShellConfig {
    val url = host.setting(ShellKeys.WS_URL)?.takeIf { it.isNotBlank() } ?: host.defaultDaemonUrl
    val fromFile = host.setting(ShellKeys.TOKEN_FILE)?.takeIf { it.isNotBlank() }
        ?.let { host.readTextFile(it)?.trim()?.takeIf { token -> token.isNotEmpty() } }
    val token = fromFile ?: host.setting(ShellKeys.TOKEN)?.takeIf { it.isNotBlank() }
    return ShellConfig(url, token, host.setting(ShellKeys.OWNER_TOKEN)?.trim()?.takeIf { it.isNotEmpty() })
}
