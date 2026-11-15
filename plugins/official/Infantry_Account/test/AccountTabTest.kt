// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.account

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabFactory
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.tabIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountTabTest {
    /** A tiny stand-in for another plugin's service: it adds one section to the Account tab and knows nothing else about it. */
    private val acme = InfantryModule(
        ModuleManifest(name = "Acme_Section", version = "1.0.0", author = "Acme", description = "A test provider section.", tabs = listOf(TabDeclaration("acme.account.section", TabContainers.CENTER))),
        listOf(
            TabProvider(
                id = "acme.account.section", container = TabContainers.CENTER, order = ACCOUNT_SECTION_ORDER_BASE + 10,
                title = { "Acme" }, icon = tabIcon("acme", { moveTo(1f, 1f); lineTo(2f, 2f) }), testTag = "center-tab-ACME", parent = ACCOUNT_TAB_ID,
            ) { },
        ),
    )

    @Test
    fun `the Account tab draws Overview and Billing, and a second plugin's section joins them without any setting`() {
        val alone = TabFactory(listOf(InfantryAccount))
        assertTrue(alone.grouped(ACCOUNT_TAB_ID), "the sections are on by default")
        assertEquals(listOf(ACCOUNT_OVERVIEW_TAB_ID, ACCOUNT_BILLING_TAB_ID), alone.children(ACCOUNT_TAB_ID).map { it.id })
        assertEquals(listOf(ACCOUNT_TAB_ID), alone.tabs(TabContainers.CENTER).map { it.id }, "only the Account tab is in the strip")

        val both = TabFactory(listOf(InfantryAccount, acme))
        assertEquals(listOf(ACCOUNT_OVERVIEW_TAB_ID, ACCOUNT_BILLING_TAB_ID, "acme.account.section"), both.children(ACCOUNT_TAB_ID).map { it.id })
        assertEquals(ACCOUNT_TAB_ID, both.hostOf("acme.account.section"))
        assertEquals(listOf(ACCOUNT_TAB_ID), both.tabs(TabContainers.CENTER).map { it.id })
    }

    @Test
    fun `the state answer is parsed to display data and holds no token field`() {
        val text = """{"providers":[{"id":"stub","name":"Stub Cloud","icon":"cloud","signedIn":true,
            "user":{"name":"Alice","email":"a***@example.org"},"plan":{"id":"basic","name":"Basic","status":"past_due"},
            "billingUrl":"https://example.org/billing","services":[{"id":"w","name":"Widgets","entitled":false}],
            "flow":{"id":"x","state":"pending","userCode":"ABCD-EFGH","verificationUri":"https://example.org/activate","expiresAt":5}}]}"""
        val p = AccountParser.parse(text)!!.single()
        assertEquals("Stub Cloud", p.name)
        assertEquals("past_due", p.planStatus)
        assertEquals("ABCD-EFGH", p.flow!!.userCode)
        assertEquals(false, p.services.single().entitled)
        assertNull(AccountParser.parse("not json"))
        assertNull(AccountParser.parse("""{"other":1}"""))
        assertNotNull(AccountParser.parse("""{"providers":[]}"""))
    }

    private fun provider(signedIn: Boolean, status: String? = null, entitled: Boolean = false, planId: String? = null) = AccountParser.parse(
        """{"providers":[{"id":"x","name":"X","signedIn":$signedIn,${if (planId != null) """"plan":{"id":"$planId","status":"$status"},""" else ""}
        "permissions":["hosting.servers.read","odd.id"],"limits":{"servers.max":1},"reclaimableUntil":0,
        "services":[{"id":"h","name":"Hosting","entitled":$entitled}]}]}""",
    )!!

    @Test
    fun `the guide walks sign in then plan then service and is complete only when all three hold`() {
        assertEquals(0, AccountGuide.of(provider(false)).current)
        assertEquals(1, AccountGuide.of(provider(true)).current)
        assertEquals(1, AccountGuide.of(provider(true, "past_due", true, "basic")).current)
        assertEquals(2, AccountGuide.of(provider(true, "active", false, "basic")).current)
        assertTrue(AccountGuide.of(provider(true, "active", true, "basic")).complete)
    }

    @Test
    fun `an active plan reads as its id, with what it includes in plain words`() {
        val p = provider(true, "active", true, "basic").single()
        assertEquals("Basic", AccountWords.planLabel(p))
        assertTrue(p.hasPlan)
        assertEquals(listOf("Hosting: up to 1 server", "Hosting: see your servers", "odd.id"), AccountWords.included(p))
        assertEquals(false, provider(true).single().hasPlan)
    }

    @Test
    fun `past due and canceled each say their own thing`() {
        assertNull(planNotice(provider(true, "active", true, "basic").single()))
        assertTrue("Payment failed" in planNotice(provider(true, "past_due", false, "basic").single())!!)
        assertTrue("subscription ended" in planNotice(provider(true, "canceled", false, "basic").single())!!)
    }
}
