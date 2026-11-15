// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.pluginsecrets.PluginSecrets
import com.superiorsoftllc.infantry.daemon.api.pluginsecrets.SecretValue
import com.superiorsoftllc.infantry.keystore.Keystore
import com.superiorsoftllc.infantry.keystore.KeystoreHandler

/**
 * ACCOUNT-SEAM-1: [PluginSecrets] for one module. The entry name comes from [module] (the host's id for the plugin), never from
 * the plugin; the only thing a plugin supplies is a field name, checked before it reaches the keystore.
 */
class ScopedPluginSecrets(module: String, private val services: DaemonServices) : PluginSecrets {
    private val entry = KeystoreHandler.pluginSecretName(module)

    private fun checked(field: String): String {
        Keystore.checkField(field)
        require(!field.uppercase().startsWith("INFANTRY_")) { "INFANTRY_* fields are not available to a plugin" }
        return field
    }

    override fun get(field: String): SecretValue? = services.keystoreField(entry, checked(field))?.let(::SecretValue)

    override fun set(field: String, value: SecretValue) {
        services.setKeystoreField(entry, checked(field), value.reveal())
    }

    override fun clear(field: String): Boolean = services.clearKeystoreField(entry, checked(field))

    override fun toString(): String = "PluginSecrets($entry)"
}
