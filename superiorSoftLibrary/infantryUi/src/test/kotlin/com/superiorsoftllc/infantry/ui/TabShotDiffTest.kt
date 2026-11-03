// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** TAB-SHOTS-DIFF: the pixel compare behind `diffTabShots` (the "nothing in the existing UI changes" rule). */
class TabShotDiffTest {
    private fun image(w: Int = 4, h: Int = 3, argb: Int = 0xFF102030.toInt()) =
        BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply { for (y in 0 until h) for (x in 0 until w) setRGB(x, y, argb) }

    private fun BufferedImage.with(x: Int, y: Int, argb: Int) = apply { setRGB(x, y, argb) }

    @Test
    fun `identical images have no differing pixel`() {
        val d = assertIs<ShotDiff.Compared>(diffShot(image(), image()))
        assertEquals(0, d.differing)
        assertEquals(12, d.total)
        assertTrue(d.passes(tolerance = 0))
    }

    @Test
    fun `one pixel off is counted, fails at tolerance 0 and is red in the diff image`() {
        val d = assertIs<ShotDiff.Compared>(diffShot(image(), image().with(2, 1, 0xFF102031.toInt())))
        assertEquals(1, d.differing)
        assertEquals(12, d.total)
        assertFalse(d.passes(tolerance = 0))
        assertEquals(DIFF_PIXEL, d.diffImage.getRGB(2, 1))
        assertTrue(d.diffImage.getRGB(0, 0) != DIFF_PIXEL)
    }

    @Test
    fun `a size mismatch never passes, whatever the tolerance`() {
        val d = assertIs<ShotDiff.SizeMismatch>(diffShot(image(4, 3), image(5, 3)))
        assertEquals("4x3", d.expected)
        assertEquals("5x3", d.actual)
        assertFalse(d.passes(tolerance = Int.MAX_VALUE))
    }

    @Test
    fun `a masked region is not compared and not counted in the total`() {
        val actual = image().with(0, 0, 0xFFFFFFFF.toInt()).with(1, 0, 0xFFFFFFFF.toInt())
        val d = assertIs<ShotDiff.Compared>(diffShot(image(), actual, mask = listOf(MaskRegion(0, 0, 2, 1))))
        assertEquals(0, d.differing)
        assertEquals(10, d.total)
        assertEquals(MASKED_PIXEL, d.diffImage.getRGB(1, 0))
        // A pixel just outside the mask is still compared.
        val outside = assertIs<ShotDiff.Compared>(diffShot(image(), actual.with(2, 0, 0xFFFFFFFF.toInt()), mask = listOf(MaskRegion(0, 0, 2, 1))))
        assertEquals(1, outside.differing)
    }

    @Test
    fun `tolerance is the number of differing pixels allowed`() {
        val d = assertIs<ShotDiff.Compared>(diffShot(image(), image().with(0, 0, 0).with(3, 2, 0)))
        assertEquals(2, d.differing)
        assertFalse(d.passes(tolerance = 1))
        assertTrue(d.passes(tolerance = 2))
    }

    @Test
    fun `the mask file names regions per shot or for every shot`() {
        val mask = parseShotMask(
            """
            # clock in the status bar
            * 1300 840 100 20
            center-tab-BOARD 10 20 30 40

            """.trimIndent(),
        )
        assertEquals(listOf(MaskRegion(1300, 840, 100, 20), MaskRegion(10, 20, 30, 40)), mask.regionsFor("center-tab-BOARD"))
        assertEquals(listOf(MaskRegion(1300, 840, 100, 20)), mask.regionsFor("right-tab-REVIEW"))
    }

    @Test
    fun `a malformed mask line is refused with its line number`() {
        val e = runCatching { parseShotMask("* 1 2 3\n") }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException && e.message!!.contains("line 1"), "got $e")
    }

    @Test
    fun `comparing folders reports each shot, a missing shot and a new shot, and writes diff images`() {
        val root = Files.createTempDirectory("tab-shot-diff").toFile()
        val base = File(root, "base").apply { mkdirs() }
        val current = File(root, "current").apply { mkdirs() }
        val diffs = File(root, "diff")
        ImageIO.write(image(), "png", File(base, "same.png"))
        ImageIO.write(image(), "png", File(current, "same.png"))
        ImageIO.write(image(), "png", File(base, "changed.png"))
        ImageIO.write(image().with(1, 1, 0), "png", File(current, "changed.png"))
        ImageIO.write(image(), "png", File(base, "gone.png"))
        ImageIO.write(image(), "png", File(current, "new.png"))

        val reports = diffShotFolders(base, current, diffs, ShotMask.NONE, tolerance = 0)

        assertEquals(
            listOf(
                "changed: 1 of 12 pixels differ",
                "gone: missing from the current shots",
                "new: not in the baseline",
                "same: 0 of 12 pixels differ",
            ),
            reports.map { it.line },
        )
        assertEquals(listOf(false, false, false, true), reports.map { it.passed })
        assertTrue(File(diffs, "changed.diff.png").isFile)
        assertFalse(File(diffs, "same.diff.png").exists())
    }
}
