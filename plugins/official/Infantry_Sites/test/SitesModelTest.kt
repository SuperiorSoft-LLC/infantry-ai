// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.sites

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SitesModelTest {
    private val utc = TimeZone.UTC

    @Test
    fun `epoch milliseconds and ISO strings become a readable date`() {
        assertEquals("Nov 21, 2026", SitesParser.date("1795219200000", utc))
        assertEquals("Nov 21, 2026", SitesParser.date(1795219200000L, utc))
        assertEquals("Nov 14, 2026", SitesParser.date("2026-11-14T10:00:00Z", utc))
    }

    @Test
    fun `blank or garbage shows nothing, never the raw value`() {
        for (bad in listOf(null, "", "   ", "soon", "12ab", "99999999999999999999999")) assertNull(SitesParser.date(bad, utc), "'$bad'")
        assertNull(SitesParser.date(null as Long?, utc))
    }

    @Test
    fun `the state keeps reclaimableUntil and the new texts`() {
        val s = SitesParser.parse("""{"state":"not_entitled","reclaimableUntil":1795219200000,"signUpUrl":"https://x.example/h"}""")!!
        assertEquals(1795219200000L, s.reclaimableUntil)
        assertNull(SitesParser.parse("""{"state":"ready","servers":[]}""")!!.reclaimableUntil)
        assertEquals("Verify your email address to create a server.", SitesParser.message("email_unverified", "x"))
    }
}

class SitesReadyTest {
    @Test
    fun `setting up shows for new and for running without ssh, a missing sshReady is ready`() {
        assertEquals("Setting up…", siteStatusLabel("new"))
        assertEquals("Setting up…", siteStatusLabel("running", sshReady = false))
        assertEquals("Online", siteStatusLabel("running", sshReady = true))
        assertEquals("Offline", siteStatusLabel("off", sshReady = false))
        val st = SitesParser.parse("""{"state":"ready","servers":[{"id":"a","status":"running"},{"id":"b","status":"running","sshReady":false}]}""")!!
        assertEquals(listOf(true, false), st.servers.map { it.sshReady })
    }
}
