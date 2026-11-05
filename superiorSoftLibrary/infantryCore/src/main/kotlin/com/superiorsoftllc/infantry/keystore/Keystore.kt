// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.keystore

import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.session.InfantryEnv

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
    /** The key comes from `INFANTRY_KEYSTORE_KEY` / `INFANTRY_KEYSTORE_KEY_FILE` (a mounted secret): headless, remote, Docker. */
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

/**
 * The file. v1 held one sealed value per name in [entries] (AAD = the name). v2 (SECRETS-2) holds named fields per secret
 * in [secrets] (AAD = name, NUL, field). A v1 entry reads as one field named [Keystore.VALUE_FIELD] and is re-sealed as v2
 * on the next write; one that does not open (another key) stays in [entries] untouched.
 */
@Serializable
private class StoreFile(
    val v: Int = 2,
    val entries: Map<String, Sealed> = emptyMap(),
    val secrets: Map<String, Map<String, Sealed>> = emptyMap(),
)

@Serializable
private class Sealed(val nonce: String, val ct: String)

/** What a client may learn about one secret: whether it is set and a masked tail. Never the value. */
data class SecretInfo(val name: String, val set: Boolean, val tail: String)

/** SECRETS-2: one field of a secret as a client may see it: its name and a masked tail. Never the value. */
data class FieldInfo(val name: String, val tail: String)

/**
 * SECRETS-1: the daemon-owned keystore, AES-256-GCM sealed in `<stateDir>/keystore.json`.
 * SECRETS-2: a secret is a set of named fields (`android-signing` -> storeFile, storePassword, ...), each sealed with the
 * secret's name and the field's name as the AAD, so a field copied under another field or secret does not open.
 * [get] / [field] / [fields] are for in-process consumers on the daemon's side only; no wire frame returns a value.
 * Nothing here ever puts a value in an exception message or [toString].
 */
class Keystore(
    private val dir: File,
    private val env: (String) -> String? = InfantryEnv::get,
    private val vault: OsVault? = OsVaults.detect(dir),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(dir, FILE_NAME)
    private val random = SecureRandom()
    private var key: ByteArray? = null
    private var how: Protection? = null

    @Synchronized
    fun protection(): Protection = ensureKey().second

    /** The [VALUE_FIELD] field: the single-value secrets of SECRETS-1 (the update token, a saved server's token). */
    @Synchronized
    fun get(name: String): String? = field(name, VALUE_FIELD)

    @Synchronized
    fun set(name: String, value: String) = setField(name, VALUE_FIELD, value)

    /**
     * Removes the whole secret, every field of it. SECRETS-2b: [project] null = the app scope (shared by every project),
     * else that project's own secret of the same name.
     */
    @Synchronized
    fun clear(name: String, project: String? = null): Boolean {
        val key = key(name, project)
        val current = read()
        if (key !in current.secrets && key !in current.entries) return false
        write(current, current.secrets - key, current.entries - key)
        return true
    }

    @Synchronized
    fun field(name: String, field: String, project: String? = null): String? {
        val key = key(name, project)
        checkField(field)
        return opened(read())[key]?.get(field)
    }

    /** Every field of [name] in [project] (null = app scope) that opens, in name order; null when there is no such secret. Daemon side only. */
    @Synchronized
    fun fields(name: String, project: String? = null): Map<String, String>? {
        val key = key(name, project)
        return opened(read())[key]?.toSortedMap()
    }

    /**
     * SECRETS-2b: the secret a run or agent of [project] means by [name]: the project's own first, then the app's.
     * Null when neither holds it. Daemon side only.
     */
    @Synchronized
    fun resolve(name: String, project: String?): Map<String, String>? =
        project?.takeIf { it.isNotBlank() }?.let { fields(name, it) } ?: fields(name)

    /** SECRETS-2b: where [resolve] finds [name] for [project]: [SCOPE_PROJECT], [SCOPE_APP], or null (in neither). */
    @Synchronized
    fun scopeOf(name: String, project: String?): String? = when {
        !project.isNullOrBlank() && key(name, project) in storedKeys() -> SCOPE_PROJECT
        key(name, null) in storedKeys() -> SCOPE_APP
        else -> null
    }

    @Synchronized
    fun setField(name: String, field: String, value: String, project: String? = null) {
        val key = key(name, project)
        checkField(field)
        val clean = value.trim()
        require(clean.isNotEmpty()) { "the secret is empty" }
        require(clean.length <= MAX_LENGTH) { "the secret is longer than $MAX_LENGTH characters" }
        val current = read()
        val fields = current.secrets[key].orEmpty()
        val maxFields = Keystore.maxFields
        val maxSecrets = Keystore.maxSecrets
        require(field in fields || fields.size < maxFields) { "a secret holds at most $maxFields fields" }
        require(key in current.secrets || key in current.entries || current.secrets.size < maxSecrets) {
            "the keystore holds at most $maxSecrets secrets"
        }
        write(current, current.secrets + (key to fields + (field to seal(key, field, clean))))
    }

    /** Removes one field; the secret goes with its last field. */
    @Synchronized
    fun deleteField(name: String, field: String, project: String? = null): Boolean {
        val key = key(name, project)
        checkField(field)
        val current = read()
        val legacyOnly = field == VALUE_FIELD && key in current.entries && current.secrets[key]?.containsKey(field) != true
        if (legacyOnly) {
            write(current, current.secrets, current.entries - key)
            return true
        }
        val fields = current.secrets[key] ?: return false
        if (field !in fields) return false
        val rest = fields - field
        write(current, if (rest.isEmpty()) current.secrets - key else current.secrets + (key to rest))
        return true
    }

    /**
     * SECRETS-2b: moves secret [name] from scope [from] to scope [to] (null = app scope), every field re-sealed under its new
     * place, in one write. The value never leaves the daemon. Refused when the source is missing or the target already exists.
     */
    @Synchronized
    fun move(name: String, from: String?, to: String?) {
        val src = key(name, from)
        val dst = key(name, to)
        require(src != dst) { "the secret is already there" }
        val current = read()
        val fields = opened(current)[src] ?: throw IllegalArgumentException("no such secret")
        require(dst !in current.secrets && dst !in current.entries) {
            "a secret named '$name' already exists in ${if (to == null) "the app scope" else "project '$to'"}"
        }
        val resealed = fields.mapValues { (f, v) -> seal(dst, f, v) }
        write(current, current.secrets - src + (dst to resealed), current.entries - src)
    }

    /** The names of the stored secrets of [project] (null = the app scope), sorted. */
    @Synchronized
    fun names(project: String? = null): List<String> {
        val prefix = project?.let { checkProject(it); "$PROJECT_MARK$it/" }
        return storedKeys().mapNotNull { k ->
            when {
                prefix == null -> k.takeUnless { it.startsWith(PROJECT_MARK) }
                k.startsWith(prefix) -> k.removePrefix(prefix)
                else -> null
            }
        }.sorted()
    }

    /** SECRETS-2b: every project that holds at least one secret of its own, sorted. */
    @Synchronized
    fun projects(): List<String> = storedKeys().filter { it.startsWith(PROJECT_MARK) }
        .map { it.removePrefix(PROJECT_MARK).substringBefore('/') }.distinct().sorted()

    /** Each field of [name] in [project] with a masked tail (fields that do not open are left out); empty when there is no such secret. */
    @Synchronized
    fun describe(name: String, project: String? = null): List<FieldInfo> = fields(name, project).orEmpty().map { (f, v) -> FieldInfo(f, maskedTail(v)) }

    private fun storedKeys(): Set<String> = read().let { it.secrets.keys + it.entries.keys }

    /**
     * SECRETS-2b: where a secret is stored: its name in the app scope, `@<project>/<name>` in a project's. Neither '@' nor '/'
     * occurs in a name, so the two never clash, and the key is the AAD's first part, so a sealed field moved between scopes
     * by hand does not open.
     */
    private fun key(name: String, project: String?): String {
        checkName(name)
        if (project == null) return name
        checkProject(project)
        return "$PROJECT_MARK$project/$name"
    }

    /** Set / not set and a masked tail for each of [names]. */
    @Synchronized
    fun status(names: Collection<String>): List<SecretInfo> = names.map { name ->
        val value = get(name)
        SecretInfo(name, value != null, value?.let(::maskedTail).orEmpty())
    }

    /** Every secret's fields that open: v2 fields, then any v1 entry as its [VALUE_FIELD] field. */
    private fun opened(content: StoreFile): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, MutableMap<String, String>>()
        for ((name, fields) in content.secrets) for ((f, sealed) in fields) {
            runCatching { open(sealed, aad(name, f)) }.getOrNull()?.let { out.getOrPut(name) { LinkedHashMap() }[f] = it }
        }
        for ((name, sealed) in content.entries) {
            if (out[name]?.containsKey(VALUE_FIELD) == true) continue
            runCatching { open(sealed, name) }.getOrNull()?.let { out.getOrPut(name) { LinkedHashMap() }[VALUE_FIELD] = it }
        }
        return out
    }

    private fun read(): StoreFile =
        if (!file.isFile) StoreFile() else runCatching { json.decodeFromString(StoreFile.serializer(), file.readText()) }.getOrElse { StoreFile() }

    /**
     * Writes v2: [secrets] plus every [legacy] v1 entry that opens, re-sealed as its [VALUE_FIELD] field. A v1 entry that
     * does not open (another key) is kept as it was, never dropped.
     */
    private fun write(current: StoreFile, secrets: Map<String, Map<String, Sealed>>, legacy: Map<String, Sealed> = current.entries) {
        val merged = secrets.toMutableMap()
        val kept = LinkedHashMap<String, Sealed>()
        for ((name, sealed) in legacy) {
            if (merged[name]?.containsKey(VALUE_FIELD) == true) continue
            val value = runCatching { open(sealed, name) }.getOrNull()
            if (value == null) { kept[name] = sealed; continue }
            merged[name] = merged[name].orEmpty() + (VALUE_FIELD to seal(name, VALUE_FIELD, value))
        }
        dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(StoreFile.serializer(), StoreFile(entries = kept, secrets = merged)))
        restrict(tmp)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun seal(name: String, field: String, value: String): Sealed {
        val nonce = ByteArray(12).also(random::nextBytes)
        return Sealed(b64(nonce), b64(cipher(Cipher.ENCRYPT_MODE, nonce, aad(name, field)).doFinal(value.toByteArray())))
    }

    private fun open(sealed: Sealed, aad: String): String =
        String(cipher(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(sealed.nonce), aad).doFinal(Base64.getDecoder().decode(sealed.ct)))

    /** NUL never occurs in a name or a field, so no two (name, field) pairs share an AAD. */
    private fun aad(name: String, field: String) = name + '\u0000' + field

    private fun cipher(mode: Int, nonce: ByteArray, aad: String): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(ensureKey().first, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(aad.toByteArray())
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
        const val KEY_ENV = "INFANTRY_KEYSTORE_KEY"
        const val KEY_FILE_ENV = "INFANTRY_KEYSTORE_KEY_FILE"
        const val MAX_LENGTH = 4096
        /** SEC-FIX-2a: setting `limits.secrets.maxFields` (registry default). */
        val maxFields: Int
            get() = LimitRegistry.cap(LimitRegistry.KEYSTORE_MAX_FIELDS.default, LimitRegistry.KEYSTORE_MAX_FIELDS)

        /** SEC-FIX-2a: setting `limits.secrets.maxSecrets` (registry default). */
        val maxSecrets: Int
            get() = LimitRegistry.cap(LimitRegistry.KEYSTORE_MAX_SECRETS.default, LimitRegistry.KEYSTORE_MAX_SECRETS)

        /** The one field of a single-value secret, and what a SECRETS-1 entry migrates to. */
        const val VALUE_FIELD = "value"

        /** The update token (the first consumer; `INFANTRY_UPDATE_TOKEN` still overrides it). */
        const val UPDATE_TOKEN = "update-token"

        private val NAME = Regex("""^[a-z0-9][a-z0-9._-]{0,63}$""")

        /** SECRETS-2: field names are environment-variable names, since a run gets each field as one. */
        private val FIELD = Regex("""^[A-Za-z_][A-Za-z0-9_]{0,63}$""")

        fun checkName(name: String) = require(NAME.matches(name)) { "secret names are lowercase letters, digits, '.', '_' and '-'" }

        fun checkField(field: String) = require(FIELD.matches(field)) { "field names are letters, digits and '_', not starting with a digit" }

        fun isName(name: String): Boolean = NAME.matches(name)

        /** SECRETS-2b: a project id as a secret scope: letters, digits, '.', '_' and '-' (no '/', so it cannot leave its prefix). */
        private val PROJECT = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$""")

        fun checkProject(project: String) = require(PROJECT.matches(project)) { "invalid project id" }

        /** The prefix of a project-scoped key (see [key]). */
        private const val PROJECT_MARK = "@"

        /** SECRETS-2b: the two scopes a secret lives in. */
        const val SCOPE_APP = "app"
        const val SCOPE_PROJECT = "project"

        /** The last 4 characters, and only for a value long enough that 4 reveal little; shorter values show only dots. */
        fun maskedTail(value: String): String = if (value.length >= 16) "••••${value.takeLast(4)}" else "••••"
    }
}
