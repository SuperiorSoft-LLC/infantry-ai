// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.keystore

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * VAULT-REVEAL-1: the vault backup file, "in case I lose it". The OS master key ties the keystore to one user on one PC, so
 * a lost PC loses every secret; a backup is every secret sealed under a key derived from a passphrase the owner types:
 * PBKDF2-HMAC-SHA256 ([KDF], the iteration count recorded in the file) and AES-256-GCM, the whole header as the AAD, so a
 * changed salt, count or format does not open. Useless without the passphrase, so it may sit on a USB stick or in the cloud.
 *
 * PBKDF2 is the JDK's own KDF (scrypt / Argon2 would need a crypto dependency in core); the count is a setting
 * (`limits.secrets.backupKdfIterations`, 600 000 by default, OWASP's figure for PBKDF2-HMAC-SHA256).
 */
object VaultBackup {
    const val FORMAT = "infantry-vault-backup"
    const val VERSION = 1
    const val KDF = "PBKDF2WithHmacSHA256"
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val random = SecureRandom()

    /** The file: a plain header and the sealed [Payload]. */
    @Serializable
    class File(val format: String, val v: Int, val kdf: String, val iterations: Int, val salt: String, val nonce: String, val ct: String)

    /** One secret in a backup. [toString] names no value. */
    @Serializable
    class Entry(val name: String, val project: String? = null, val fields: Map<String, String>) {
        /** How an import names it to the owner, and the key of its conflict answer: `name` (app scope) or `project/name`. */
        val id: String get() = if (project == null) name else "$project/$name"
        override fun toString(): String = "Entry(id=$id, fields=${fields.keys})"
    }

    @Serializable
    class Payload(val createdAt: Long, val entries: List<Entry>) {
        override fun toString(): String = "Payload(createdAt=$createdAt, entries=${entries.size})"
    }

    /** The passphrase did not open the backup (or the file was changed). */
    class WrongPassphrase : IllegalArgumentException("the passphrase does not open this backup (or the file was changed)")

    fun seal(entries: List<Entry>, passphrase: CharArray, iterations: Int, now: Long = System.currentTimeMillis()): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val head = File(FORMAT, VERSION, KDF, iterations, b64(salt), b64(nonce), "")
        val plain = json.encodeToString(Payload.serializer(), Payload(now, entries)).toByteArray(Charsets.UTF_8)
        try {
            val ct = cipher(Cipher.ENCRYPT_MODE, passphrase, head).doFinal(plain)
            return json.encodeToString(File.serializer(), File(FORMAT, VERSION, KDF, iterations, head.salt, head.nonce, b64(ct)))
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Opens [text]. Refused with a plain reason when it is not a backup, names another format or KDF, or asks for an
     * iteration count outside [iterationRange] (a crafted file could otherwise pin the daemon); [WrongPassphrase] when the
     * passphrase (or a changed byte) does not open it.
     */
    fun open(text: String, passphrase: CharArray, iterationRange: LongRange): Payload {
        val file = runCatching { json.decodeFromString(File.serializer(), text) }.getOrNull()
            ?: throw IllegalArgumentException("this is not an Infantry AI vault backup")
        require(file.format == FORMAT) { "this is not an Infantry AI vault backup" }
        require(file.v == VERSION) { "this backup is format version ${file.v}; this daemon reads version $VERSION" }
        require(file.kdf == KDF) { "this backup uses an unknown key derivation" }
        require(file.iterations.toLong() in iterationRange) { "this backup's key derivation count is outside ${iterationRange.first}..${iterationRange.last}" }
        val ct = runCatching { Base64.getDecoder().decode(file.ct) }.getOrElse { throw IllegalArgumentException("this backup is damaged") }
        val head = File(file.format, file.v, file.kdf, file.iterations, file.salt, file.nonce, "")
        val plain = try {
            cipher(Cipher.DECRYPT_MODE, passphrase, head).doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw WrongPassphrase()
        }
        try {
            return runCatching { json.decodeFromString(Payload.serializer(), String(plain, Charsets.UTF_8)) }
                .getOrElse { throw IllegalArgumentException("this backup is damaged") }
        } finally {
            plain.fill(0)
        }
    }

    private fun cipher(mode: Int, passphrase: CharArray, head: File): Cipher {
        val salt = runCatching { Base64.getDecoder().decode(head.salt) }.getOrElse { throw IllegalArgumentException("this backup is damaged") }
        val nonce = runCatching { Base64.getDecoder().decode(head.nonce) }.getOrElse { throw IllegalArgumentException("this backup is damaged") }
        require(salt.size == SALT_BYTES && nonce.size == NONCE_BYTES) { "this backup is damaged" }
        val spec = PBEKeySpec(passphrase, salt, head.iterations, KEY_BITS)
        val key = try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(aad(head))
            }
        } finally {
            key.fill(0)
        }
    }

    /** The header binds the ciphertext: format, version, KDF, count, salt and nonce. */
    private fun aad(head: File): ByteArray = listOf(head.format, head.v, head.kdf, head.iterations, head.salt, head.nonce).joinToString("\u0000").toByteArray()

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
}
