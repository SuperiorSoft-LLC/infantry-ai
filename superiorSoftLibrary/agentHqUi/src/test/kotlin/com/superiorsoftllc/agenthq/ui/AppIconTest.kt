package com.superiorsoftllc.agenthq.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** APP-ICON-1: the window, taskbar and installer carry the AgentHQ "A" mark, not Compose's default Kotlin icon. */
class AppIconTest {
    @Test fun theWindowIconPainterLoadsTheAgentHqMark() {
        val painter = AppIcon.painter()
        assertNotNull(painter, "AppIcon.painter() must load ${AppIcon.RESOURCE} from the classpath")
        assertEquals(256f, painter.intrinsicSize.width, "the window icon is the 256px AgentHQ mark")
        // The mark's tile is Palette.Accent amber: sample the centre-left of the tile, away from the "A".
        val image = AppIcon.image()!!
        val argb = image.getRGB(image.width / 8 + 4, image.height / 2)
        assertEquals(0xFFF59E0B.toInt(), argb, "the icon tile must be the header logo's amber (Palette.Accent)")
    }

    @Test fun theSharedWindowPassesTheIcon() {
        val src = File("src/main/kotlin/com/superiorsoftllc/agenthq/ui/AgentHqWindow.kt").readText()
        val call = src.substringAfter("Window(onCloseRequest = onCloseRequest").substringBefore(")")
        assertTrue("icon = icon" in call, "AgentHqWindow's Window(...) must pass icon = icon, was: $call")
        assertTrue("val icon = remember { AppIcon.painter() }" in src, "AgentHqWindow's icon must be AppIcon.painter()")
    }

    @Test fun theInstallerUsesTheAgentHqIco() {
        val build = File("build.gradle.kts").readText()
        val windows = build.substringAfter("windows {").substringBefore("}")
        assertTrue("iconFile.set(project.file(\"icons/agenthq.ico\"))" in windows, "nativeDistributions.windows must set iconFile to icons/agenthq.ico")
        val ico = File("icons/agenthq.ico")
        assertTrue(ico.isFile && ico.length() > 0, "icons/agenthq.ico must exist")
        val head = ico.readBytes().take(4)
        assertEquals(listOf<Byte>(0, 0, 1, 0), head, "icons/agenthq.ico must be an ICO file")
    }
}
