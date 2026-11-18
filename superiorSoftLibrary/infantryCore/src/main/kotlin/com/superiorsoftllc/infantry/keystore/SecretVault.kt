// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.keystore

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** VAULT-REVEAL-1 `secret_reveal`: field [field] of secret [name] in [project] (null = app scope); [purpose] reveal | copy. */
@Serializable
class SecretRevealRequest(val name: String, val field: String = Keystore.VALUE_FIELD, val project: String? = null, val purpose: String = PURPOSE_REVEAL) {
    companion object {
        const val PURPOSE_REVEAL = "reveal"
        const val PURPOSE_COPY = "copy"
    }
}

/**
 * The direct answer to [SecretRevealRequest], never pushed: the value, how the person was verified, and how long the window
 * may show it ([showSeconds]) or keep it on the clipboard ([clearSeconds]). [toString] names no value.
 */
@Serializable
class SecretRevealed(
    val name: String, val field: String, val project: String? = null, val value: String, val method: String,
    val showSeconds: Long, val clearSeconds: Long,
) {
    override fun toString(): String = "SecretRevealed(name=$name, field=$field, project=$project, method=$method)"
}

/** `secret_backup_export`: write every secret, sealed with [passphrase], to the new file [path] on the daemon's machine. */
@Serializable
class BackupExportRequest(val path: String, val passphrase: String) {
    override fun toString(): String = "BackupExportRequest(path=$path)"
}

@Serializable
class BackupExported(val path: String, val secrets: Int, val method: String)

/**
 * `secret_backup_import`: merge the backup at [path] into the keystore. [resolutions] answers each conflict (a secret that
 * exists here with other values) by its id (`name`, or `project/name`): keep | replace. While a conflict is unanswered
 * nothing is written and the answer lists the conflicts, so the window asks per conflict and sends again.
 */
@Serializable
class BackupImportRequest(val path: String, val passphrase: String, val resolutions: Map<String, String> = emptyMap()) {
    override fun toString(): String = "BackupImportRequest(path=$path, resolutions=${resolutions.size})"

    companion object {
        const val KEEP = "keep"
        const val REPLACE = "replace"
    }
}

/** The answer to an import: [applied] false = nothing written, [conflicts] still need an answer. Ids only, never a value. */
@Serializable
class BackupImported(
    val applied: Boolean,
    val added: List<String> = emptyList(),
    val replaced: List<String> = emptyList(),
    val kept: List<String> = emptyList(),
    val same: List<String> = emptyList(),
    val conflicts: List<String> = emptyList(),
)

/**
 * VAULT-REVEAL-1: the owner's window reads a stored value back, or exports the vault, only after the person at this computer
 * passes the OS user check ([OsUserVerifier]: Windows Hello first). Frames `secret_reveal`, `secret_backup_export` and
 * `secret_backup_import` are owner-only (FrameGate), refused under a restricted policy, and answered directly with the
 * request's own type (never pushed). No CLI command, MCP tool, script API or daemon-plugin API reaches this class: agents
 * keep `{{secret:...}}` rendering only (VaultNoAgentPathGuardTest).
 *
 * Every reveal, copy, export and import is appended to [auditFile] (JSON lines: time, action, secret, field, scope, method,
 * outcome). Never a value or a passphrase.
 */
class SecretVault(
    private val keystore: Keystore,
    private val verifier: OsUserVerifier,
    private val auditFile: File,
    private val restricted: () -> Boolean = { false },
    private val limit: (LimitDef) -> Long = { it.default },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called after an import wrote secrets (the daemon pushes `secret_state`). */
    private val onImported: () -> Unit = {},
    private val home: String = System.getProperty("user.home").orEmpty(),
) {
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    private val verifyLock = Any()
    private var lastVerified: Pair<Long, String>? = null

    fun answer(envelope: Envelope): Envelope? {
        val type = envelope.type
        if (type !in FRAMES) return null
        if (restricted()) return Envelope(Envelope.ERROR, "$type refused: secrets are locked under a restricted policy")
        return try {
            when (type) {
                Envelope.SECRET_REVEAL -> reveal(json.decodeFromString(SecretRevealRequest.serializer(), envelope.data))
                Envelope.SECRET_BACKUP_EXPORT -> export(json.decodeFromString(BackupExportRequest.serializer(), envelope.data))
                else -> import(json.decodeFromString(BackupImportRequest.serializer(), envelope.data))
            }
        } catch (e: SerializationException) {
            // A decode message quotes the input (a passphrase): say only that the frame is malformed.
            Envelope(Envelope.ERROR, "$type refused: malformed request")
        } catch (e: IllegalArgumentException) {
            Envelope(Envelope.ERROR, "$type refused: ${e.message ?: "invalid request"}")
        } catch (e: Exception) {
            Envelope(Envelope.ERROR, "$type failed: ${e::class.simpleName}")
        }
    }

    private fun reveal(req: SecretRevealRequest): Envelope {
        require(req.purpose == SecretRevealRequest.PURPOSE_REVEAL || req.purpose == SecretRevealRequest.PURPOSE_COPY) { "purpose must be reveal or copy" }
        Keystore.checkName(req.name)
        Keystore.checkField(req.field)
        val project = scope(req.project)
        // Ask the OS only for a value that exists: the check leaks nothing a `secret_state` does not already show.
        require(keystore.fields(req.name, project)?.containsKey(req.field) == true) { "no such secret field" }
        val shown = if (project == null) req.name else "$project/${req.name}"
        val check = verified("Infantry AI: ${req.purpose} the secret $shown#${req.field}")
        audit(req.purpose, req.name, req.field, project, check)
        require(check.verified) { refusal(check) }
        val value = keystore.field(req.name, req.field, project) ?: throw IllegalArgumentException("no such secret field")
        val answer = SecretRevealed(
            req.name, req.field, project, value, check.method,
            showSeconds = limit(LimitRegistry.SECRET_REVEAL_SECONDS), clearSeconds = limit(LimitRegistry.SECRET_CLIPBOARD_CLEAR_SECONDS),
        )
        return Envelope(Envelope.SECRET_REVEAL, json.encodeToString(SecretRevealed.serializer(), answer))
    }

    private fun export(req: BackupExportRequest): Envelope {
        checkPassphrase(req.passphrase)
        val target = path(req.path)
        require(!target.exists()) { "${target.path} already exists; choose a new file name" }
        val check = verified("Infantry AI: export an encrypted backup of every secret")
        audit("export", "*", "", null, check, target.path)
        require(check.verified) { refusal(check) }
        val entries = keystore.snapshot().map { VaultBackup.Entry(it.name, it.project, it.fields) }
        val text = VaultBackup.seal(entries, req.passphrase.toCharArray(), limit(LimitRegistry.SECRET_BACKUP_KDF_ITERATIONS).toInt(), clock())
        target.absoluteFile.parentFile?.mkdirs()
        val tmp = File(target.absoluteFile.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return Envelope(Envelope.SECRET_BACKUP_EXPORT, json.encodeToString(BackupExported.serializer(), BackupExported(target.path, entries.size, check.method)))
    }

    private fun import(req: BackupImportRequest): Envelope {
        val source = path(req.path)
        require(source.isFile) { "${source.path} is not a file" }
        val max = limit(LimitRegistry.SECRET_BACKUP_MAX_BYTES)
        require(source.length() <= max) { "${source.path} is bigger than $max bytes (limits.secrets.backupMaxBytes)" }
        val bad = req.resolutions.filterValues { it != BackupImportRequest.KEEP && it != BackupImportRequest.REPLACE }.keys
        require(bad.isEmpty()) { "a conflict is answered keep or replace" }
        val kdf = LimitRegistry.SECRET_BACKUP_KDF_ITERATIONS
        val payload = VaultBackup.open(source.readText(), req.passphrase.toCharArray(), kdf.min..(kdf.max ?: Long.MAX_VALUE))
        val added = ArrayList<String>(); val replaced = ArrayList<String>(); val kept = ArrayList<String>()
        val same = ArrayList<String>(); val conflicts = ArrayList<String>()
        val writes = ArrayList<VaultBackup.Entry>()
        for (entry in payload.entries) {
            Keystore.checkName(entry.name)
            val project = scope(entry.project)
            entry.fields.keys.forEach(Keystore::checkField)
            val here = keystore.fields(entry.name, project)
            when {
                here == null -> { added += entry.id; writes += entry }
                here == entry.fields.mapValues { it.value.trim() } -> same += entry.id
                req.resolutions[entry.id] == BackupImportRequest.REPLACE -> { replaced += entry.id; writes += entry }
                req.resolutions[entry.id] == BackupImportRequest.KEEP -> kept += entry.id
                else -> conflicts += entry.id
            }
        }
        if (conflicts.isNotEmpty()) {
            return Envelope(Envelope.SECRET_BACKUP_IMPORT, json.encodeToString(BackupImported.serializer(), BackupImported(false, conflicts = conflicts)))
        }
        for (entry in writes) keystore.replaceSecret(entry.name, scope(entry.project), entry.fields)
        appendAudit("import", "*", "", null, "", "ok", source.path + " (added ${added.size}, replaced ${replaced.size}, kept ${kept.size})")
        if (writes.isNotEmpty()) runCatching(onImported)
        return Envelope(Envelope.SECRET_BACKUP_IMPORT, json.encodeToString(BackupImported.serializer(), BackupImported(true, added, replaced, kept, same)))
    }

    /** One OS verification, or the one before it while `limits.secrets.verifyCacheSeconds` still covers it. One prompt at a time. */
    private fun verified(reason: String): Verification = synchronized(verifyLock) {
        val cacheMs = limit(LimitRegistry.SECRET_VERIFY_CACHE_SECONDS) * 1_000L
        val now = clock()
        lastVerified?.let { (at, method) -> if (cacheMs > 0 && now - at in 0 until cacheMs) return Verification(Verification.Outcome.VERIFIED, "$method (cached)") }
        val result = runCatching { verifier.verify(reason) }.getOrElse { Verification.unavailable("the verification prompt failed") }
        lastVerified = if (result.verified) clock() to result.method else null
        result
    }

    private fun refusal(check: Verification): String = when (check.outcome) {
        Verification.Outcome.DENIED -> "${verifier.label} verification was not passed (${check.detail.ifBlank { "denied" }})"
        else -> check.detail.ifBlank { "${verifier.label} is not available here" }
    }

    private fun checkPassphrase(passphrase: String) {
        val min = limit(LimitRegistry.SECRET_BACKUP_MIN_PASSPHRASE)
        require(passphrase.length >= min) { "the passphrase needs at least $min characters (limits.secrets.backupMinPassphrase)" }
    }

    /** A path on the daemon's machine: `~` is the daemon user's home; it must then be absolute. */
    private fun path(raw: String): File {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "give the backup file's path" }
        val expanded = if (trimmed == "~" || trimmed.startsWith("~/") || trimmed.startsWith("~\\")) home + trimmed.drop(1) else trimmed
        val file = File(expanded)
        require(file.isAbsolute) { "give a full path (or one starting with ~)" }
        return file
    }

    private fun scope(project: String?): String? = project?.trim()?.takeIf { it.isNotEmpty() }?.also(Keystore::checkProject)

    private fun audit(action: String, name: String, field: String, project: String?, check: Verification, detail: String = "") =
        appendAudit(action, name, field, project, check.method, check.outcome.name.lowercase(), detail.ifBlank { check.detail })

    /** One JSON line per action. Its arguments are names, scopes and outcomes; never a value. */
    @Synchronized
    private fun appendAudit(action: String, name: String, field: String, project: String?, method: String, outcome: String, detail: String) {
        runCatching {
            val now = clock()
            val line = buildJsonObject {
                put("at", JsonPrimitive(Instant.ofEpochMilli(now).toString()))
                put("action", JsonPrimitive(action))
                put("secret", JsonPrimitive(name))
                if (field.isNotEmpty()) put("field", JsonPrimitive(field))
                project?.let { put("project", JsonPrimitive(it)) }
                put("method", JsonPrimitive(method))
                put("outcome", JsonPrimitive(outcome))
                if (detail.isNotEmpty()) put("detail", JsonPrimitive(detail))
            }.toString()
            auditFile.absoluteFile.parentFile?.mkdirs()
            auditFile.appendText(line + "\n")
        }
    }

    companion object {
        const val AUDIT_FILE_NAME = "vault-audit.jsonl"
        val FRAMES = setOf(Envelope.SECRET_REVEAL, Envelope.SECRET_BACKUP_EXPORT, Envelope.SECRET_BACKUP_IMPORT)
    }
}
