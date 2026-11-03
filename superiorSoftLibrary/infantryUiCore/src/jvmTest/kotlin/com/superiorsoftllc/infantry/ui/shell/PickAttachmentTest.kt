package com.superiorsoftllc.infantry.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ANDROID-ATTACH-1: the content-based picker contract every host answers (name, mime, bytes; oversize = size only). */
class PickAttachmentTest {
    private fun host(path: String?, size: Long?, data: ByteArray?) = object : PlatformHost {
        override val defaultDaemonUrl = "ws://x/ws"
        override fun setting(key: String): String? = null
        override fun readTextFile(path: String): String? = null
        override val canPickFiles get() = true
        override fun pickFile(title: String): String? = path
        override fun fileSize(path: String): Long? = size
        override fun readBytes(path: String): ByteArray? = data
    }

    private fun pick(h: PlatformHost, max: Long): PickedImage? {
        var out: PickedImage? = null
        h.pickAttachment("t", false, max) { out = it }
        return out
    }

    @Test fun cancelledGivesNull() = assertNull(pick(host(null, null, null), 100))

    @Test fun imageKeepsNameMimeAndBytes() {
        val p = assertNotNull(pick(host("C:/pics/cat.PNG", 3, byteArrayOf(1, 2, 3)), 100))
        assertEquals("cat.PNG", p.fileName); assertEquals("image/png", p.mimeType); assertTrue(p.isImage); assertEquals(3, p.size)
    }

    @Test fun otherFileHasNoImageMime() {
        val p = assertNotNull(pick(host("/a/b/doc.pdf", 2, byteArrayOf(1, 2)), 100))
        assertEquals("", p.mimeType); assertEquals(false, p.isImage)
    }

    @Test fun oversizeIsRefusedUnreadButKeepsItsSize() {
        val p = assertNotNull(pick(host("/a/big.bin", 500, byteArrayOf(9)), 100))
        assertEquals(0, p.bytes.size); assertEquals(500, p.size)
    }
}
