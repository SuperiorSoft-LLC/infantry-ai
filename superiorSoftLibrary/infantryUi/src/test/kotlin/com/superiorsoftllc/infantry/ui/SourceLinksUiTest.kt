// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.ReportFacts
import com.superiorsoftllc.infantry.ui.model.SourceLinks
import com.superiorsoftllc.infantry.ui.model.UiFlags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CONTRIB-1: About's Contribute / Report a problem / View on GitHub come from `urls.sourceRepo`, and hide when it is blank. */
@OptIn(ExperimentalTestApi::class)
class SourceLinksUiTest {
    private val repo = "https://git.example.com/acme/thing"
    private val facts = ReportFacts("1.2.3+20261105.1200.abc1234", "1.2.3+20261105.1200.abc1234", "Windows 11 10.0 (amd64), desktop", "claude")

    @Test fun `links are built from the setting`() {
        val l = SourceLinks.of("$repo/", facts)!!
        assertEquals("$repo/blob/main/CONTRIBUTING.md", l.contribute)
        assertEquals(repo, l.repo)
        assertTrue(l.reportProblem.startsWith("$repo/issues/new?title=Problem%3A%20&body="), l.reportProblem)
        val body = l.reportProblem.substringAfter("body=")
        assertTrue(body.contains(SourceLinks.encode("App build: 1.2.3+20261105.1200.abc1234")))
        assertTrue(body.contains(SourceLinks.encode("OS: Windows 11 10.0 (amd64), desktop")))
        assertTrue(body.contains(SourceLinks.encode("Harness mode: claude")))
        assertTrue(body.none { it == ' ' || it == '\n' || it == '&' || it == '#' })
    }

    @Test fun `blank, missing or non-http setting gives no links`() {
        assertNull(SourceLinks.of(null, facts))
        assertNull(SourceLinks.of("  ", facts))
        assertNull(SourceLinks.of("javascript:alert(1)", facts))
    }

    @Test fun `settings frame carries the repo`() {
        assertEquals(repo, SourceLinks.repoFromSettingsFrame("""{"urls.sourceRepo":"$repo","x":1}"""))
        assertNull(SourceLinks.repoFromSettingsFrame("""{"x":1}"""))
    }

    @Test fun `the three buttons open their URLs`() = runDesktopComposeUiTest(900, 900) {
        val opened = mutableListOf<String>()
        val links = SourceLinks.of(repo, facts)!!
        setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened += uri } }) {
                AboutSection(AboutEntry("a", "a", daemonConnected = true, links = links))
            }
        }
        for (t in listOf(ABOUT_CONTRIBUTE_TAG, ABOUT_REPORT_PROBLEM_TAG, ABOUT_VIEW_REPO_TAG)) onNodeWithTag(t).performClick()
        waitForIdle()
        assertEquals(listOf(links.contribute, links.reportProblem, links.repo), opened)
    }

    @Test fun `no setting, no buttons`() = runDesktopComposeUiTest(900, 900) {
        setContent { ConfigDialog(UiFlags(), onFlags = {}, onDismiss = {}, about = AboutEntry("a", "a", daemonConnected = true)) }
        onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.ABOUT.name).performClick()
        onNodeWithTag(ABOUT_APP_BUILD_TAG).performScrollTo()
        onNodeWithTag(ABOUT_APP_BUILD_TAG).assertExists()
        for (t in listOf(ABOUT_CONTRIBUTE_TAG, ABOUT_REPORT_PROBLEM_TAG, ABOUT_VIEW_REPO_TAG)) {
            assertEquals(0, onAllNodesWithTag(t).fetchSemanticsNodes().size)
        }
    }

    @Test fun `ABOUT-SITE-1 website line opens the site and hides without it`() = runDesktopComposeUiTest(900, 900) {
        assertEquals("https://x.example/", SourceLinks.siteFromSettingsFrame("""{"urls.site":"https://x.example/"}"""))
        assertNull(SourceLinks.siteFromSettingsFrame("""{"urls.site":"javascript:1"}"""))
        assertNull(SourceLinks.siteFromSettingsFrame("""{"x":1}"""))
        val opened = mutableListOf<String>()
        val site = androidx.compose.runtime.mutableStateOf<String?>("https://x.example/")
        setContent {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened += uri } }) {
                AboutSection(AboutEntry("a", "a", daemonConnected = true, website = site.value))
            }
        }
        onNodeWithTag(ABOUT_WEBSITE_TAG).performClick()
        waitForIdle()
        assertEquals(listOf("https://x.example/"), opened)
        site.value = null
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(ABOUT_WEBSITE_TAG).fetchSemanticsNodes().size)
    }
}
