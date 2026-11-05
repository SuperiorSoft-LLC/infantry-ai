// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.session.InfantryEnv

import com.superiorsoftllc.infantry.session.infantrySetting
import com.superiorsoftllc.infantry.session.stateDir
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties

/**
 * The persisted remote-access setting: `<stateDir>/network.properties` (`remote=true|false`, optional `host=`,
 * optional `port=`), read at startup alongside the environment. The environment always wins when it is set, and
 * the default (no file, or `remote=false`) is the unchanged loopback-only daemon.
 *
 * With `remote=true` and no token from the environment, a token is generated ONCE into `<stateDir>/remote.token`
 * and reused. [AccessPolicy] is not touched: a non-loopback bind still refuses to start without a token.
 */
object RemoteAccess {
    const val SETTINGS_FILE = "network.properties"
    const val TOKEN_FILE = "remote.token"
    const val TOKEN_BYTES = 33 // 44 base64url characters

    data class Settings(val remote: Boolean = false, val host: String? = null, val port: Int? = null)

    fun settingsFile(dir: File): File = File(dir, SETTINGS_FILE)
    fun tokenFile(dir: File): File = File(dir, TOKEN_FILE)

    /** A missing or unreadable file is "off": the default must never fail a start. */
    fun load(dir: File): Settings {
        val file = settingsFile(dir)
        if (!file.isFile) return Settings()
        val props = Properties()
        runCatching { file.reader(Charsets.UTF_8).use(props::load) }.getOrElse { return Settings() }
        return parse(props)
    }

    fun parse(props: Properties): Settings = Settings(
        remote = props.getProperty("remote")?.trim()?.equals("true", ignoreCase = true) == true,
        host = props.getProperty("host")?.trim()?.takeIf { it.isNotEmpty() },
        port = props.getProperty("port")?.trim()?.toIntOrNull()?.takeIf { it in 0..65535 },
    )

    fun save(dir: File, settings: Settings) {
        dir.mkdirs()
        val props = Properties()
        props.setProperty("remote", settings.remote.toString())
        settings.host?.let { props.setProperty("host", it) }
        settings.port?.let { props.setProperty("port", it.toString()) }
        settingsFile(dir).writer(Charsets.UTF_8).use { props.store(it, "Infantry AI remote access") }
    }

    /** The stored token, or null when there is none (or it is unreadable). */
    fun storedToken(dir: File): String? {
        val file = tokenFile(dir)
        if (!file.isFile) return null
        return runCatching { AccessPolicy.readTokenFile(file) }.getOrNull()?.takeIf { it.length >= MIN_TOKEN_LENGTH }
    }

    /** The stored token, generating and storing one first if there is none. Generated once, then reused. */
    fun ensureToken(dir: File, random: SecureRandom = SecureRandom()): String {
        storedToken(dir)?.let { return it }
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        dir.mkdirs()
        val file = tokenFile(dir)
        file.writeText(token, Charsets.UTF_8)
        // Owner-only where the file system supports POSIX permissions; Windows files inherit the user profile ACL.
        runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        return token
    }

    /**
     * The bind host: `INFANTRY_HOST` when set; else, with `remote=true`, the file's `host=` or the wildcard
     * `0.0.0.0` (loopback clients keep working, the Tailscale address is reachable); else loopback.
     * NS5: a system property of the same name wins ([infantrySetting]), so a shell app pins its bundled daemon to loopback.
     */
    fun resolveHost(dir: File, getenv: (String) -> String? = { infantrySetting(it) }): String {
        getenv("INFANTRY_HOST")?.let { return it }
        val settings = load(dir)
        return if (settings.remote) settings.host ?: "0.0.0.0" else "127.0.0.1"
    }

    /** The token the daemon runs with: the environment's, else (remote on) the stored/generated one, else none. */
    fun resolveToken(
        dir: File,
        getenv: (String) -> String? = InfantryEnv::get,
        isolated: Boolean = credentialsIsolated(),
    ): String? {
        if (!isolated) AccessPolicy.tokenFromEnvironment(getenv)?.let { return it }
        return if (load(dir).remote) ensureToken(dir) else null
    }

    /**
     * SEC (ticket 6d635c2f): set (as a system property, by `AppDaemonProfile`) for an exported app's bundled daemon. The
     * process environment is the OWNER's (the app was launched from the owner's shell or Infantry), so its
     * `INFANTRY_TOKEN` / `INFANTRY_TOKEN_FILE` (and `INFANTRY_*` aliases) are the owner credential: under this setting the
     * daemon and its in-process client ignore them (the token comes from the app's own state dir only), and every
     * spawned agent gets them blanked ([inheritedCredentialScrub]).
     */
    const val ISOLATED_CREDENTIALS_SETTING = "INFANTRY_ISOLATED_CREDENTIALS"

    fun credentialsIsolated(setting: (String) -> String? = { infantrySetting(it) }): Boolean =
        setting(ISOLATED_CREDENTIALS_SETTING)?.trim().equals("true", ignoreCase = true)

    /** Every environment name a credential is read from: `INFANTRY_TOKEN`, `INFANTRY_TOKEN_FILE` and their `INFANTRY_*` aliases. */
    val CREDENTIAL_ENV: List<String> = listOf(AccessPolicy.TOKEN_ENV, AccessPolicy.TOKEN_FILE_ENV)
        .flatMap { listOfNotNull(it, InfantryEnv.aliasOf(it)) }

    /**
     * The child-env overrides that blank every inherited credential name (a spawned process inherits the parent's
     * environment; a blank value reads as unset everywhere a token is resolved). Merged UNDER the daemon's own token.
     */
    fun inheritedCredentialScrub(): Map<String, String> = CREDENTIAL_ENV.associateWith { "" }

    /**
     * DEMO-CHECK-1: the child-env entries that hand a spawned process THIS daemon's [token]: every inherited credential
     * name blanked, then `INFANTRY_TOKEN` set. A daemon started with `INFANTRY_TOKEN_FILE` (the docker image does) used to
     * pass that name on too, so every `infantry` child (the agents' `infantry mcp serve`) saw both and refused to start
     * ("set INFANTRY_TOKEN_FILE or INFANTRY_TOKEN, not both"): no agent of such a daemon had its infantry tools.
     */
    fun daemonTokenEnv(token: String): Map<String, String> = inheritedCredentialScrub() + (AccessPolicy.TOKEN_ENV to token)

    private val PAT_VALUE = Regex("^(ghp_|gho_|ghu_|ghs_|ghr_|github_pat_)[A-Za-z0-9_]{20,}$")

    /**
     * RUN-CMD-REPO-1: the child-env overrides that blank every inherited SECRET an agent session must never see: any
     * `*_UPDATE_TOKEN` (the updater's token, a GitHub PAT) and any variable whose value is a GitHub PAT. Always on (unlike
     * [inheritedCredentialScrub]). `INFANTRY_TOKEN` is NOT scrubbed: the daemon sets it per agent on purpose, so the CLI and the
     * curl a delivered prompt carries can authenticate against this daemon.
     */
    fun inheritedSecretScrub(inherited: Map<String, String> = System.getenv()): Map<String, String> =
        inherited.filter { (k, v) -> k.uppercase().endsWith("_UPDATE_TOKEN") || PAT_VALUE.matches(v.trim()) }.keys.associateWith { "" }

    /** The port from the file; the environment (`INFANTRY_PORT`) is consulted by the caller first. */
    fun filePort(dir: File): Int? = load(dir).port

    /**
     * The token a client on THIS machine should present when the environment names none: the stored token, and
     * only while remote access is on. Null otherwise, so a default install sends nothing, as before.
     */
    fun clientToken(getenv: (String) -> String? = InfantryEnv::get, isolated: Boolean = credentialsIsolated()): String? {
        if (!isolated) AccessPolicy.tokenFromEnvironment(getenv)?.let { return it }
        return runCatching {
            val dir = stateDir { getenv(it) ?: infantrySetting(it) }
            if (load(dir).remote) storedToken(dir) else null
        }.getOrNull()
    }

    /** The first IPv4 address in 100.64.0.0/10 (Tailscale's CGNAT range) on this machine, or null. */
    fun tailscaleAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress }
            .firstOrNull(::isTailscaleAddress)
    }.getOrNull()

    fun isTailscaleAddress(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        return parts.size == 4 && parts[0] == 100 && parts[1] in 64..127
    }
}
