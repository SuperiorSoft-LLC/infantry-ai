// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.account.daemon

import com.superiorsoftllc.infantry.keystore.Keystore
import kotlin.test.Test
import kotlin.test.assertFalse

/** ACCOUNT-SECRET-NAME-1: every field the plugin can produce passes the host's secret-field rules (Keystore.checkField + the INFANTRY_ prefix refusal of ScopedPluginSecrets). */
class SecretFieldNameTest {
    @Test
    fun `refresh field names pass the host rules for infantry, stub and odd ids`() {
        for (id in listOf("infantry", "stub", "a_b_9", "x")) {
            val field = "acct_${id}_refresh"
            Keystore.checkField(field)
            assertFalse(field.uppercase().startsWith("INFANTRY_"), field)
        }
    }
}
