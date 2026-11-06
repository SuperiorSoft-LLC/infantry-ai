// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.model.BuildCheck
import com.superiorsoftllc.infantry.ui.model.BuildStamp

const val ABOUT_APP_BUILD_TAG = "about-app-build"
const val ABOUT_DAEMON_BUILD_TAG = "about-daemon-build"
const val ABOUT_BUILD_MISMATCH_TAG = "about-build-mismatch"
const val FOOTER_BUILD_TAG = "footer-build"
const val ABOUT_CONTRIBUTE_TAG = "about-contribute"
const val ABOUT_REPORT_PROBLEM_TAG = "about-report-problem"
const val ABOUT_VIEW_REPO_TAG = "about-view-repo"
const val ABOUT_WEBSITE_TAG = "about-website"

/** BUILD-STAMP-1: Configuration > About. Which build this app is, which build the connected daemon is, and whether they differ. */
class AboutEntry(
    val appBuild: String?,
    val daemonBuild: String?,
    val daemonConnected: Boolean,
    /** CONTRIB-1: the source-repository links; null hides them (no `urls.sourceRepo`). */
    val links: com.superiorsoftllc.infantry.ui.model.SourceLinks? = null,
    /** ABOUT-SITE-1: the product website (daemon `urls.site`); null hides the line. */
    val website: String? = null,
) {
    companion object {
        const val TITLE = "About"
        const val APPLIES_TO = "The exact build of this app and of the daemon it is connected to (version, UTC build time, commit)."
    }
}

@Composable
fun AboutSection(about: AboutEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                BuildLine("App", about.appBuild, "not stamped (a development run)", ABOUT_APP_BUILD_TAG)
                BuildLine(
                    "Daemon", about.daemonBuild,
                    if (about.daemonConnected) "not reported (a daemon older than build stamps)" else "not connected",
                    ABOUT_DAEMON_BUILD_TAG,
                )
            }
        }
        BuildCheck.mismatch(about.appBuild, about.daemonBuild)?.let {
            Text(it, color = Palette.Danger, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag(ABOUT_BUILD_MISMATCH_TAG))
        }
        about.website?.let { WebsiteLine(it) }
        about.links?.let { SourceLinkButtons(it) }
    }
}

/** ABOUT-SITE-1: "Website: <url>", clickable; the platform's UriHandler opens it. */
@Composable
private fun WebsiteLine(url: String) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    Text(
        "Website: $url", color = Palette.Info, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.testTag(ABOUT_WEBSITE_TAG)
            .semantics { contentDescription = "Website $url (opens in the browser)" }
            .clickable { runCatching { uri.openUri(url) } },
    )
}

/** CONTRIB-1: Contribute / Report a problem / View on GitHub. The platform's UriHandler opens them (desktop browser, Android intent, web new tab). */
@Composable
private fun SourceLinkButtons(links: com.superiorsoftllc.infantry.ui.model.SourceLinks) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        LinkButton("Contribute", AhqIcons.Branch, ABOUT_CONTRIBUTE_TAG) { uri.openUri(links.contribute) }
        LinkButton("Report a problem", AhqIcons.Activity, ABOUT_REPORT_PROBLEM_TAG) { uri.openUri(links.reportProblem) }
        LinkButton("View on GitHub", AhqIcons.OpenInFull, ABOUT_VIEW_REPO_TAG) { uri.openUri(links.repo) }
    }
}

@Composable
private fun LinkButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, onClick: () -> Unit) {
    OutlinedButton(onClick = { runCatching(onClick) }, modifier = Modifier.testTag(tag).semantics { contentDescription = "$label (opens in the browser)" }) {
        androidx.compose.material3.Icon(icon, contentDescription = null, tint = Palette.Info, modifier = Modifier.size(16.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun BuildLine(label: String, build: String?, missing: String, tag: String) {
    val stamp = BuildStamp.parse(build)
    Column {
        Text("$label: ${stamp?.raw ?: missing}", fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag(tag))
        stamp?.detail?.let { Text(it, color = if (stamp.dirty) Palette.Accent else Palette.Muted, fontSize = 11.sp) }
    }
}

/** BUILD-STAMP-1: the footer's build text (bottom right, next to the cog); the danger colour when app and daemon differ. */
@Composable
fun FooterBuild(appBuild: String?, daemonBuild: String?, modifier: Modifier = Modifier) {
    val (text, warn) = BuildCheck.footer(appBuild, daemonBuild)
    Text(
        text, color = if (warn) Palette.Danger else Palette.Muted, fontSize = 10.sp, maxLines = 1,
        fontWeight = if (warn) FontWeight.Bold else FontWeight.Normal,
        modifier = modifier.testTag(FOOTER_BUILD_TAG).semantics { contentDescription = if (warn) "Build mismatch: $text" else "App $text" },
    )
}
