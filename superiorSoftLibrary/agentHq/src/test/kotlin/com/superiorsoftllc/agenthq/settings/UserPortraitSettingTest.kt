package com.superiorsoftllc.agenthq.settings

import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import kotlinx.serialization.json.JsonPrimitive
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** USER-PORTRAIT (contract 1.107.0): `user.portrait`, the human's picture, stored by the daemon for every client. */
class UserPortraitSettingTest {
    private val dir: File = Files.createTempDirectory("agenthq-portrait").toFile()
    private val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath)
    private val settings = SettingsService(store)

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun png(side: Int): String {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(side, side, BufferedImage.TYPE_INT_RGB), "png", out)
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    @Test
    fun absentWhileUnset_storedDurably_clearedByEmptyString() {
        assertFalse(settings.all().containsKey("user.portrait"), "unset = no key: clients show their bundled default")
        assertNull(settings.userPortrait())

        val image = png(64)
        assertEquals(JsonPrimitive(image), settings.set("user.portrait", JsonPrimitive(image))["user.portrait"])
        assertEquals(image, SettingsService(store).userPortrait(), "durable, read back by a fresh service")

        assertFalse(settings.set("user.portrait", JsonPrimitive("")).containsKey("user.portrait"), "reset to default")
        assertNull(settings.userPortrait())
    }

    @Test
    fun refusesNonImagesBadBase64OversizeAndNonStrings_changingNothing() {
        val image = png(32)
        settings.set("user.portrait", JsonPrimitive(image))
        val notAnImage = Base64.getEncoder().encodeToString("hello".toByteArray())
        val oversize = Base64.getEncoder().encodeToString(ByteArray(300 * 1024) { 1 })
        for (bad in listOf(JsonPrimitive("%%%"), JsonPrimitive(notAnImage), JsonPrimitive(oversize), JsonPrimitive(5), JsonPrimitive(true))) {
            assertFailsWith<IllegalArgumentException>("$bad") { settings.set("user.portrait", bad) }
        }
        assertEquals(image, settings.userPortrait(), "a refused value changes nothing")
    }
}
