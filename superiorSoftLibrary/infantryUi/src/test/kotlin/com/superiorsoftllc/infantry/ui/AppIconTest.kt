// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** APP-ICON-1: the window, taskbar and installer carry the Infantry AI emblem, not Compose's default Kotlin icon. */
class AppIconTest {
    @Test fun theWindowIconPainterLoadsTheInfantryMark() {
        val painter = AppIcon.painter()
        assertNotNull(painter, "AppIcon.painter() must load ${AppIcon.RESOURCE} from the classpath")
        assertEquals(256f, painter.intrinsicSize.width, "the window icon is the 256px emblem")
        // The emblem is drawn on a transparent canvas (icons/GenInfantryIcon.java): an empty corner, an opaque centre.
        val image = AppIcon.image()!!
        assertEquals(0, image.getRGB(0, 0) ushr 24, "the icon's corner must be transparent")
        val centre = image.getRGB(image.width / 2, image.height / 2) ushr 24
        assertTrue(centre >= 0xC0, "the emblem must be opaque at its centre, alpha was $centre")
    }

    @Test fun theSharedWindowPassesTheIcon() {
        val src = File("src/main/kotlin/com/superiorsoftllc/infantry/ui/InfantryWindow.kt").readText()
        val call = src.substringAfter("Window(onCloseRequest = onCloseRequest").substringBefore(")")
        assertTrue("icon = icon" in call, "InfantryWindow's Window(...) must pass icon = icon, was: $call")
        assertTrue("val icon = remember { AppIcon.painter() }" in src, "InfantryWindow's icon must be AppIcon.painter()")
    }

    @Test fun theInstallerUsesTheInfantryIco() {
        val build = File("build.gradle.kts").readText()
        val windows = build.substringAfter("windows {").substringBefore("}")
        assertTrue("iconFile.set(project.file(\"icons/infantry.ico\"))" in windows, "nativeDistributions.windows must set iconFile to icons/infantry.ico")
        val ico = File("icons/infantry.ico")
        assertTrue(ico.isFile && ico.length() > 0, "icons/infantry.ico must exist")
        val head = ico.readBytes().take(4)
        assertEquals(listOf<Byte>(0, 0, 1, 0), head, "icons/infantry.ico must be an ICO file")
    }
}
