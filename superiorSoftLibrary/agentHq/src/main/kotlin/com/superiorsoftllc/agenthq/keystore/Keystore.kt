package com.superiorsoftllc.agenthq.keystore

import com.superiorsoftllc.agenthq.session.InfantryEnv

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** How the keystore's master key is protected. Reported by `secret_state`; never the key itself. */
enum class Protection(val wire: String) {
    /** The key comes from `AGENTHQ_KEYSTORE_KEY` / `AGENTHQ_KEYSTORE_KEY_FILE` (a mounted secret): headless, remote, Docker. */
    EXTERNAL_KEY("external-key"),

    /** The key is held by the OS facility (Windows DPAPI, macOS Keychain, Linux Secret Service). */
    OS("os"),

    /** Documented fallback: the key sits in `<stateDir>/keystore.key` (owner-only permissions). Encrypted at rest, but only as strong as the state dir. */
    KEY_FILE("key-file"),
}

/** Where the master key lives in the OS (one per platform; see [OsVaults]). */
interface OsVault {
    val label: String
    fun fetch(): String?
    fun store(value: String): Boolean
}

@Serializable
private class StoreFile(val v: Int = 1, val entries: Map<String, Sealed> = emptyMap())

@Serializable
private class Sealed(val nonce: String, val ct: String)

/** What a client may learn about one secret: whether it is set and a masked tail. Never the value. */
data class SecretInfo(val name: String, val set: Boolean, val tail: String)

/**
 * SECRETS-1: the daemon-owned keystore. Values are AES-256-GCM sealed (the entry name is the AAD) in `<stateDir>/keystore.json`.
 * [get] is for in-process consumers on the daemon's side only; no wire frame returns a value. Nothing here ever puts a value in
 * an exception message or [toString].
 */
class Keystore(
    private val dir: File,
    private val env: (String) -> String? = InfantryEnv::get,
    private val vault: OsVault? = OsVaults.detect(dir),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(dir, FILE_NAME)
    private val random = SecureRandom()
    private var key: ByteArray? = null
    private var how: Protection? = null

    @Synchronized
    fun protection(): Protection = ensureKey().second

    @Synchronized
    fun get(name: String): String? {
        checkName(name)
        val sealed = read().entries[name] ?: return null
        return runCatching { open(sealed, name) }.getOrNull()
    }

    @Synchronized
    fun set(name: String, value: String) {
        checkName(name)
        val clean = value.trim()
        require(clean.isNotEmpty()) { "the secret is empty" }
        require(clean.length <= MAX_LENGTH) { "the secret is longer than $MAX_LENGTH characters" }
        val nonce = ByteArray(12).also(random::nextBytes)
        val ct = cipher(Cipher.ENCRYPT_MODE, nonce, name).doFinal(clean.toByteArray())
        write(StoreFile(entries = read().entries + (name to Sealed(b64(nonce), b64(ct)))))
    }

    @Synchronized
    fun clear(name: String): Boolean {
        checkName(name)
        val current = read()
        if (name !in current.entries) return false
        write(StoreFile(entries = current.entries - name))
        return true
    }

    /** Set / not set and a masked tail for each of [names]. */
    @Synchronized
    fun status(names: Collection<String>): List<SecretInfo> = names.map { name ->
        val value = get(name)
        SecretInfo(name, value != null, value?.let(::maskedTail).orEmpty())
    }

    private fun read(): StoreFile =
        if (!file.isFile) StoreFile() else runCatching { json.decodeFromString(StoreFile.serializer(), file.readText()) }.getOrElse { StoreFile() }

    private fun write(content: StoreFile) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(StoreFile.serializer(), content))
        restrict(tmp)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun open(sealed: Sealed, name: String): String =
        String(cipher(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(sealed.nonce), name).doFinal(Base64.getDecoder().decode(sealed.ct)))

    private fun cipher(mode: Int, nonce: ByteArray, name: String): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(ensureKey().first, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(name.toByteArray())
    }

    /** Resolution order: mounted/env key, then the OS vault, then the key file. Never silently plaintext. */
    private fun ensureKey(): Pair<ByteArray, Protection> {
        key?.let { return it to how!! }
        val external = env(KEY_ENV)?.takeIf { it.isNotBlank() }
            ?: env(KEY_FILE_ENV)?.takeIf { it.isNotBlank() }?.let { path ->
                File(path).takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() } ?: error("$KEY_FILE_ENV points at a missing or empty file")
            }
        val (bytes, protection) = when {
            external != null -> MessageDigest.getInstance("SHA-256").digest(external.trim().toByteArray()) to Protection.EXTERNAL_KEY
            else -> osKey() ?: fileKey()
        }
        key = bytes
        how = protection
        return bytes to protection
    }

    private fun osKey(): Pair<ByteArray, Protection>? {
        val v = vault ?: return null
        v.fetch()?.let { return Base64.getDecoder().decode(it) to Protection.OS }
        val fresh = ByteArray(32).also(random::nextBytes)
        return if (v.store(b64(fresh))) fresh to Protection.OS else null
    }

    private fun fileKey(): Pair<ByteArray, Protection> {
        val f = File(dir, KEY_FILE_NAME)
        if (f.isFile) return Base64.getDecoder().decode(f.readText().trim()) to Protection.KEY_FILE
        dir.mkdirs()
        val fresh = ByteArray(32).also(random::nextBytes)
        f.writeText(b64(fresh))
        restrict(f)
        return fresh to Protection.KEY_FILE
    }

    private fun restrict(f: File) {
        runCatching { Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------")) }
    }

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    override fun toString(): String = "Keystore(dir=$dir)"

    companion object {
        const val FILE_NAME = "keystore.json"
        const val KEY_FILE_NAME = "keystore.key"
        const val KEY_ENV = "AGENTHQ_KEYSTORE_KEY"
        const val KEY_FILE_ENV = "AGENTHQ_KEYSTORE_KEY_FILE"
        const val MAX_LENGTH = 4096

        /** The update token (the first consumer; `AGENTHQ_UPDATE_TOKEN` still overrides it). */
        const val UPDATE_TOKEN = "update-token"

        private val NAME = Regex("""^[a-z0-9][a-z0-9._-]{0,63}$""")

        fun checkName(name: String) = require(NAME.matches(name)) { "secret names are lowercase letters, digits, '.', '_' and '-'" }

        /** The last 4 characters, and only for a value long enough that 4 reveal little; shorter values show only dots. */
        fun maskedTail(value: String): String = if (value.length >= 16) "••••${value.takeLast(4)}" else "••••"
    }
}
