// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.android

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** APP-ICON-1: the Android launcher shows the Infantry "A" mark, not the system default icon. */
class LauncherIconTest {
    private val res = File("src/main/res")

    @Test fun theManifestNamesTheLauncherIcon() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val app = manifest.substringAfter("<application").substringBefore(">")
        assertTrue("android:icon=\"@mipmap/ic_launcher\"" in app, "<application> must set android:icon=\"@mipmap/ic_launcher\"")
    }

    @Test fun everyDensityHasALegacyLauncherPng() {
        for (d in listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")) {
            val f = File(res, "mipmap-$d/ic_launcher.png")
            assertTrue(f.isFile && f.length() > 0, "$f must exist (minSdk 24 needs the legacy PNG)")
        }
    }

    @Test fun api26HasAnAdaptiveIcon() {
        val f = File(res, "mipmap-anydpi-v26/ic_launcher.xml")
        assertTrue(f.isFile, "$f must exist")
        val xml = f.readText()
        assertTrue("<adaptive-icon" in xml && "@mipmap/ic_launcher_foreground" in xml, "$f must be an adaptive icon with the A foreground")
    }
}
