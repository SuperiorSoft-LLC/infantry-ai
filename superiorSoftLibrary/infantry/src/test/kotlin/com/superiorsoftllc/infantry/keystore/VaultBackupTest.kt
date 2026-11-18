// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.keystore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** VAULT-REVEAL-1: the passphrase-sealed backup file (PBKDF2-HMAC-SHA256 + AES-256-GCM, header as AAD). */
class VaultBackupTest {
    private val pass = "a long test passphrase".toCharArray()
    private val range = 1_000L..10_000_000L
    private val entries = listOf(
        VaultBackup.Entry("openai", null, mapOf("value" to "sk-test-backup-value-123")),
        VaultBackup.Entry("db", "proj-1", mapOf("user" to "app", "password" to "db-secret-456")),
    )

    @Test
    fun `round trip keeps every entry and the file holds no value`() {
        val text = VaultBackup.seal(entries, pass, 1_000, now = 42)
        assertFalse("sk-test-backup-value-123" in text)
        assertFalse("db-secret-456" in text)
        val back = VaultBackup.open(text, pass, range)
        assertEquals(42, back.createdAt)
        assertEquals(entries.map { it.id to it.fields }, back.entries.map { it.id to it.fields })
        assertEquals("proj-1/db", back.entries[1].id)
    }

    @Test
    fun `a wrong passphrase fails`() {
        val text = VaultBackup.seal(entries, pass, 1_000)
        assertFailsWith<VaultBackup.WrongPassphrase> { VaultBackup.open(text, "not the passphrase".toCharArray(), range) }
    }

    @Test
    fun `a changed header or ciphertext fails`() {
        val text = VaultBackup.seal(entries, pass, 1_000)
        val o = Json.parseToJsonElement(text).jsonObject
        assertFailsWith<VaultBackup.WrongPassphrase> { VaultBackup.open(JsonObject(o + ("iterations" to JsonPrimitive(1_001))).toString(), pass, range) }
        val ct = o["ct"]!!.jsonPrimitive.content
        val flipped = (if (ct[0] == 'A') "B" else "A") + ct.drop(1)
        assertFailsWith<VaultBackup.WrongPassphrase> { VaultBackup.open(JsonObject(o + ("ct" to JsonPrimitive(flipped))).toString(), pass, range) }
    }

    @Test
    fun `a count outside the range and a foreign file are refused`() {
        val text = VaultBackup.seal(entries, pass, 1_000)
        assertFailsWith<IllegalArgumentException> { VaultBackup.open(text, pass, 2_000L..3_000L) }
        assertFailsWith<IllegalArgumentException> { VaultBackup.open("{\"hello\":1}", pass, range) }
        assertFailsWith<IllegalArgumentException> { VaultBackup.open("not json", pass, range) }
    }

    @Test
    fun `toString names no value`() {
        assertFalse("sk-test" in entries[0].toString())
    }
}
