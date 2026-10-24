package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.superiorsoftllc.agenthq.ui.model.BuildCheck
import com.superiorsoftllc.agenthq.ui.model.BuildStamp

const val ABOUT_APP_BUILD_TAG = "about-app-build"
const val ABOUT_DAEMON_BUILD_TAG = "about-daemon-build"
const val ABOUT_BUILD_MISMATCH_TAG = "about-build-mismatch"
const val FOOTER_BUILD_TAG = "footer-build"

/** BUILD-STAMP-1: Configuration > About. Which build this app is, which build the connected daemon is, and whether they differ. */
class AboutEntry(val appBuild: String?, val daemonBuild: String?, val daemonConnected: Boolean) {
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
