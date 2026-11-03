package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.LocalFile
import com.superiorsoftllc.infantry.daemon.api.LocalFiles
import com.superiorsoftllc.infantry.daemon.api.board.TicketImages
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RUNTIME-LOAD-4 (contract 1.147.0): the local-file argument contract. The client encodes ONLY the declared keys, a regular file
 * travels as its bytes, a larger one by its size, anything else as the string given; the tool decodes each back to the same
 * [LocalFile], and the shared [TicketImages] rule judges it with the texts the CLI's tools used.
 */
class LocalFilesTest {
    private val dir: File = Files.createTempDirectory("local-files").toFile()

    @AfterTest fun cleanUp() { dir.deleteRecursively() }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

    @Test fun `declared keys travel as bytes, other arguments and missing files pass as given`() {
        val image = File(dir, "shot.png").apply { writeBytes(png) }
        val args = JsonObject(mapOf(
            "images" to JsonArray(listOf(JsonPrimitive(image.path), JsonPrimitive("nope.png"), JsonPrimitive(".infantry/uploads/ticket/ab.png"), JsonPrimitive(5))),
            "title" to JsonPrimitive(image.path),
        ))
        val sent = LocalFiles.encodeArguments(args, listOf("images"))
        assertEquals(JsonPrimitive(image.path), sent["title"], "an undeclared argument is never read")
        val items = sent["images"]!!.jsonArray
        assertEquals(image.path, items[0].jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(png.size.toLong(), items[0].jsonObject["size"]!!.jsonPrimitive.content.toLong())
        assertEquals(JsonPrimitive("nope.png"), items[1])
        assertEquals(JsonPrimitive(".infantry/uploads/ticket/ab.png"), items[2])
        assertEquals(JsonPrimitive(5), items[3], "a non-string is left for the tool to refuse")

        val back = items.map { LocalFiles.decode(it) }
        assertTrue(back[0]!!.bytes!!.contentEquals(png))
        assertEquals(LocalFile("nope.png"), back[1])
        assertNull(back[3], "a number is not a path")
        assertNull(TicketImages.check(listOf(back[0]!!, back[2]!!)), "an image and a stored path are accepted")
        assertEquals("image 'nope.png' does not exist (no such file)", TicketImages.check(listOf(back[1]!!)))
    }

    @Test fun `a file over the limit travels by its size only and is refused by it`() {
        val big = File(dir, "big.png")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(LocalFiles.MAX_BYTES + 1) }
        val encoded = LocalFiles.encode(big.path).jsonObject
        assertTrue("base64" !in encoded, "no bytes over the limit")
        val file = LocalFiles.decode(encoded)!!
        assertEquals(
            "image '${big.path}' is over the 10 MB limit (${LocalFiles.MAX_BYTES + 1} bytes, at most ${LocalFiles.MAX_BYTES})",
            TicketImages.check(listOf(file)),
        )
    }

    @Test fun `an empty or non-image file is refused, and a stored image matches its original file by sha256`() {
        val empty = LocalFiles.decode(LocalFiles.encode(File(dir, "e.png").apply { writeBytes(ByteArray(0)) }.path))!!
        assertEquals("image '${File(dir, "e.png").path}' is empty", TicketImages.check(listOf(empty)))
        val text = LocalFiles.decode(LocalFiles.encode(File(dir, "t.png").apply { writeText("hello") }.path))!!
        assertEquals("image '${File(dir, "t.png").path}' is not a png, jpeg, gif or webp image (its first bytes match none of them)", TicketImages.check(listOf(text)))
        val original = LocalFiles.decode(LocalFiles.encode(File(dir, "o.png").apply { writeBytes(png) }.path))!!
        val stored = ".infantry/uploads/ticket/${TicketImages.sha256(png)}.png"
        assertTrue(TicketImages.matches(stored, original))
        assertTrue(TicketImages.matches(stored, LocalFile("${TicketImages.sha256(png)}.png")), "by file name")
        assertTrue(!TicketImages.matches(stored, LocalFile("other.png")))
    }
}
