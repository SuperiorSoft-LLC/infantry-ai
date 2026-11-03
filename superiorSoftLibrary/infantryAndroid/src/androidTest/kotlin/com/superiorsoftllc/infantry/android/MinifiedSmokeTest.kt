// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.ImageStored
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ANDROID-MINIFY-1: device smoke for the R8-minified build. testBuildType is `minifiedSmoke` (initWith release, debug
 * signing), so this runs against the same R8 configuration as the shipped release APK.
 *
 * (1) the app under test really is minified: R8 renamed Envelope, and
 * (2) a daemon frame still decodes through the serializers R8 kept: the Envelope, then its typed `image` payload,
 *     as DaemonClient.kt (Envelope) and Controller.kt answerImageUpload (ImageStored) do it.
 * No daemon is needed: the frames below are a captured `image` answer, decoded offline.
 *
 * The androidTest APK calls the app's own minified classes, so it only uses what the app itself keeps:
 * - the default [Json] instance, not a `Json { ... }` builder (R8 inlines the builder overloads the app does not need);
 * - decode + re-encode compared to the canonical frame, never the DTO getters (R8 inlines those into the app's
 *   callers). A field dropped or renamed by R8 changes the re-encoded text.
 * - no Class.forName("<original name>"): R8 rewrites a constant class-name string to the obfuscated name.
 */
@RunWith(AndroidJUnit4::class)
class MinifiedSmokeTest {
    @Test
    fun buildUnderTestIsMinified() {
        assertNotEquals(
            "Envelope kept its original name: the build under test is NOT minified",
            "com.superiorsoftllc.infantry.ui.protocol.Envelope",
            Envelope::class.java.name,
        )
    }

    @Test
    fun daemonImageFrameDecodes() {
        val env = Json.decodeFromString(Envelope.serializer(), IMAGE_FRAME)
        assertEquals(IMAGE_FRAME, Json.encodeToString(Envelope.serializer(), env))

        val stored = Json.decodeFromString(ImageStored.serializer(), IMAGE_DATA)
        assertEquals(IMAGE_DATA, Json.encodeToString(ImageStored.serializer(), stored))
    }

    private companion object {
        /** The typed payload of the daemon's `image` answer (WireProtocol ImageStored). */
        const val IMAGE_DATA =
            """{"path":"C:/infantry/uploads/2026/shot.png","relPath":"uploads/2026/shot.png",""" +
                """"sha256":"9f86d081","bytes":2048,"mimeType":"image/png"}"""

        /** The same answer as it arrives on the WebSocket: an Envelope whose `data` is IMAGE_DATA as a JSON string. */
        const val IMAGE_FRAME =
            """{"type":"image","data":"{\"path\":\"C:/infantry/uploads/2026/shot.png\",""" +
                """\"relPath\":\"uploads/2026/shot.png\",\"sha256\":\"9f86d081\",\"bytes\":2048,""" +
                """\"mimeType\":\"image/png\"}","sessionId":"s-7f3a","sequence":42}"""
    }
}
