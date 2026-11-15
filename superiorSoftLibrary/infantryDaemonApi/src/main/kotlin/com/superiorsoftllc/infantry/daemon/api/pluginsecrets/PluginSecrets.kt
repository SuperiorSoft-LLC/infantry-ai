// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.pluginsecrets

/**
 * ACCOUNT-SEAM-1 (DaemonApi 1.62.0, additive, docs/wiki/daemon-plugin-api.md "Plugin secrets"): a secret value that cannot
 * leak by accident. [toString] never shows the value; read it with [reveal] at the one place that needs it.
 */
class SecretValue(private val value: String) {
    fun reveal(): String = value
    override fun toString(): String = "SecretValue(***)"
    override fun equals(other: Any?): Boolean = other is SecretValue && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

/**
 * ACCOUNT-SEAM-1: named fields in the CALLING plugin's own keystore entry, encrypted at rest. The host fixes the entry from the
 * module id; there is no parameter that names another module's entry. A field name is letters, digits and `_`
 * (not starting with a digit); `INFANTRY_*` names are refused. A bad name throws [IllegalArgumentException] (the message never
 * holds a value).
 */
interface PluginSecrets {
    /** The field's value, or null when unset (or the keystore is locked). */
    fun get(field: String): SecretValue?

    /** Stores [value] (not blank) in [field]; throws when the keystore is locked or full. */
    fun set(field: String, value: SecretValue)

    /** Removes [field]; true when it existed. */
    fun clear(field: String): Boolean
}

/** A daemon without a keystore: nothing is stored, every write refuses. */
object NoPluginSecrets : PluginSecrets {
    override fun get(field: String): SecretValue? = null
    override fun set(field: String, value: SecretValue) = throw UnsupportedOperationException("no keystore")
    override fun clear(field: String): Boolean = false
}
