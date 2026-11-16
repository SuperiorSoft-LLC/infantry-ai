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
import com.superiorsoftllc.infantry.ui.model.AppState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountTabTest {
    private val acmeDashboard = TabProvider(
        id = "acme.account.section", container = TabContainers.CENTER, order = 30,
        title = { "Acme" }, icon = tabIcon("acme", { moveTo(1f, 1f); lineTo(2f, 2f) }), testTag = "center-tab-ACME", parent = ACCOUNT_TAB_ID,
    ) { }

    @Test
    fun `the tab is labelled Services and a second plugin's dashboard section joins it without any setting`() {
        val alone = TabFactory(listOf(InfantryAccount))
        assertEquals(listOf(ACCOUNT_TAB_ID), alone.tabs(TabContainers.CENTER).map { it.id })
        assertEquals("Services", alone.find(ACCOUNT_TAB_ID)!!.title(AppState()))
        val both = TabFactory(listOf(InfantryAccount, InfantryModule(
            ModuleManifest(name = "Acme_Section", version = "1.0.0", author = "Acme", description = "x", tabs = listOf(TabDeclaration("acme.account.section", TabContainers.CENTER))),
            listOf(acmeDashboard),
        )))
        assertEquals(listOf("acme.account.section"), both.children(ACCOUNT_TAB_ID).map { it.id })
        assertEquals(listOf(ACCOUNT_TAB_ID), both.tabs(TabContainers.CENTER).map { it.id })
    }

    @Test
    fun `a second fake provider shows in the list, Sign in until a token exists, then Connected with its dashboard`() {
        fun list(signedIn: Boolean) = AccountParser.parse(
            """{"providers":[
              {"id":"infantry","name":"Hosting","signedIn":true,"services":[{"id":"h","name":"Hosting","entitled":true,"section":"infantry.sites"}]},
              {"id":"acme","name":"Acme Cloud","description":"Acme things","signedIn":$signedIn,"services":[{"id":"w","name":"W","entitled":false,"section":"acme.account.section"}]},
              {"id":"plain","name":"No dash","signedIn":true,"services":[]}]}""",
        )!!
        val out = list(false)
        assertEquals(listOf("infantry", "acme", "plain"), out.map { it.id })
        assertEquals("Acme things", out[1].description)
        val loaded = { id: String -> id == "acme.account.section" || id == "infantry.sites" }
        assertEquals(false, out[1].signedIn, "no token: the card shows Sign in")
        assertEquals(false, canOpenDashboard(out[1], loaded), "a signed-out card opens nothing")
        val on = list(true)
        assertEquals(true, on[1].signedIn, "token: the card shows Connected")
        assertEquals("acme.account.section", on[1].dashboardTabId)
        assertEquals(true, canOpenDashboard(on[1], loaded))
        assertEquals(false, canOpenDashboard(on[1]) { false }, "dashboard plugin not loaded: nothing opens")
        assertEquals(false, canOpenDashboard(on[2], loaded), "a provider with no dashboard: Connected only")
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
    fun `the sign-in page opens with the code attached`() {
        val f = AccountFlow("pending", "ABCD-EFGH", "https://example.org/signin", 5, null)
        assertEquals("https://example.org/signin?user_code=ABCD-EFGH", signInUrl(f))
        assertEquals("https://example.org/signin?user_code=X", signInUrl(f.copy(verificationUri = "https://example.org/signin?user_code=X")))
        assertEquals("https://example.org/a?b=1&user_code=ABCD-EFGH", signInUrl(f.copy(verificationUri = "https://example.org/a?b=1")))
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
