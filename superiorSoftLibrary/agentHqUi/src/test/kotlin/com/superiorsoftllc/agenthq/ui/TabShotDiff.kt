package com.superiorsoftllc.agenthq.ui

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * TAB-SHOTS-DIFF: pixel-by-pixel compare of tab shots against a baseline, behind the `diffTabShots` Gradle task.
 * A pixel differs when its ARGB value is not identical. Masked regions (legitimately dynamic, e.g. a clock) are
 * neither compared nor counted. Tolerance is the number of differing pixels a shot may have (0 = pixel-identical).
 */

/** In the diff image: a differing pixel, and a masked one. Everything else is the baseline, dimmed to grey. */
const val DIFF_PIXEL = 0xFFFF0000.toInt()
const val MASKED_PIXEL = 0xFF2040A0.toInt()

data class MaskRegion(val x: Int, val y: Int, val width: Int, val height: Int) {
    fun contains(px: Int, py: Int) = px >= x && px < x + width && py >= y && py < y + height
}

sealed interface ShotDiff {
    fun passes(tolerance: Int): Boolean

    /** [total] is the number of compared (unmasked) pixels. */
    data class Compared(val differing: Int, val total: Int, val diffImage: BufferedImage) : ShotDiff {
        override fun passes(tolerance: Int) = differing <= tolerance
    }

    data class SizeMismatch(val expected: String, val actual: String) : ShotDiff {
        override fun passes(tolerance: Int) = false
    }
}

fun diffShot(baseline: BufferedImage, actual: BufferedImage, mask: List<MaskRegion> = emptyList()): ShotDiff {
    val w = baseline.width
    val h = baseline.height
    if (actual.width != w || actual.height != h) return ShotDiff.SizeMismatch("${w}x$h", "${actual.width}x${actual.height}")
    val a = baseline.getRGB(0, 0, w, h, null, 0, w)
    val b = actual.getRGB(0, 0, w, h, null, 0, w)
    val out = IntArray(w * h)
    var differing = 0
    var total = 0
    for (y in 0 until h) for (x in 0 until w) {
        val i = y * w + x
        out[i] = when {
            mask.any { it.contains(x, y) } -> MASKED_PIXEL
            a[i] != b[i] -> { total++; differing++; DIFF_PIXEL }
            else -> { total++; dimmed(a[i]) }
        }
    }
    val diff = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, w, h, out, 0, w) }
    return ShotDiff.Compared(differing, total, diff)
}

/** The baseline pixel as a dark grey, so the red differing pixels stand out against the layout. */
private fun dimmed(argb: Int): Int {
    val luma = (((argb shr 16) and 0xFF) * 299 + ((argb shr 8) and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000
    val g = luma / 3
    return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
}

/** Mask regions per shot name; `*` applies to every shot. */
class ShotMask(private val regions: List<Pair<String, MaskRegion>>) {
    fun regionsFor(shot: String) = regions.filter { it.first == "*" || it.first == shot }.map { it.second }

    companion object {
        val NONE = ShotMask(emptyList())
    }
}

/** One region per line: `<shot name or *> <x> <y> <width> <height>`; blank lines and `#` comments are skipped. */
fun parseShotMask(text: String): ShotMask = ShotMask(
    text.lines().mapIndexedNotNull { index, raw ->
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty()) return@mapIndexedNotNull null
        val parts = line.split(Regex("\\s+"))
        val numbers = parts.drop(1).map { it.toIntOrNull() }
        require(parts.size == 5 && numbers.all { it != null && it >= 0 }) {
            "mask line ${index + 1}: expected '<shot or *> <x> <y> <width> <height>', got '$raw'"
        }
        parts[0] to MaskRegion(numbers[0]!!, numbers[1]!!, numbers[2]!!, numbers[3]!!)
    },
)

data class ShotReport(val name: String, val line: String, val passed: Boolean)

/**
 * Compares every `*.png` in [baselineDir] with the same name in [currentDir], sorted by name. A shot missing on either
 * side fails. A differing shot's diff image goes to [diffDir] as `<name>.diff.png`.
 */
fun diffShotFolders(baselineDir: File, currentDir: File, diffDir: File, mask: ShotMask, tolerance: Int): List<ShotReport> {
    fun pngs(dir: File) = dir.listFiles { f -> f.isFile && f.name.endsWith(".png") }.orEmpty().associateBy { it.name.removeSuffix(".png") }
    val base = pngs(baselineDir)
    val current = pngs(currentDir)
    return (base.keys + current.keys).sorted().map { name ->
        val b = base[name]
        val c = current[name]
        when {
            b == null -> ShotReport(name, "$name: not in the baseline", false)
            c == null -> ShotReport(name, "$name: missing from the current shots", false)
            else -> when (val d = diffShot(ImageIO.read(b), ImageIO.read(c), mask.regionsFor(name))) {
                is ShotDiff.SizeMismatch -> ShotReport(name, "$name: size differs (baseline ${d.expected}, current ${d.actual})", false)
                is ShotDiff.Compared -> {
                    if (d.differing > 0) ImageIO.write(d.diffImage, "png", File(diffDir.apply { mkdirs() }, "$name.diff.png"))
                    ShotReport(name, "$name: ${d.differing} of ${d.total} pixels differ", d.passes(tolerance))
                }
            }
        }
    }
}

/** Args: baseline folder, current folder, diff folder, tolerance, then an optional mask file. Exits 1 on any failure. */
fun main(args: Array<String>) {
    require(args.size >= 4) { "usage: <baseline dir> <current dir> <diff dir> <tolerance> [mask file]" }
    val baseline = File(args[0])
    val current = File(args[1])
    val diffDir = File(args[2]).apply { deleteRecursively() }
    val tolerance = args[3].toInt()
    val maskFile = args.getOrNull(4)?.let(::File)?.takeIf { it.isFile }
    require(baseline.isDirectory && baseline.listFiles { f -> f.name.endsWith(".png") }.orEmpty().isNotEmpty()) {
        "no baseline shots in $baseline: run updateTabShotBaseline, or pass -PtabShotsBaseline=<folder>"
    }
    val reports = diffShotFolders(baseline, current, diffDir, maskFile?.let { parseShotMask(it.readText()) } ?: ShotMask.NONE, tolerance)
    reports.forEach { println(if (it.passed) it.line else "${it.line}  FAIL") }
    val failed = reports.count { !it.passed }
    println("diffTabShots: ${reports.size - failed} of ${reports.size} shots pass (tolerance $tolerance px${maskFile?.let { ", mask $it" } ?: ""})")
    if (failed > 0) {
        println("diff images: $diffDir")
        System.exit(1)
    }
}
