package com.superiorsoftllc.agenthq.update

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * SERVER-UPDATE-1: unpacks a downloaded server archive (`.zip` or `.tar.gz`) into a directory. Hostile input is
 * expected: an entry that would land outside the target, a link, or more than [MAX_BYTES] in total is refused with an
 * [IOException] (the caller deletes the half-written directory). No library: the JDK has no tar reader.
 */
object ArchiveUnpacker {
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024

    fun unpack(archive: File, into: File) {
        into.mkdirs()
        val name = archive.name.lowercase()
        when {
            name.endsWith(".zip") -> archive.inputStream().buffered().use { unzip(it, into) }
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> GZIPInputStream(archive.inputStream().buffered()).use { untar(it, into) }
            else -> throw IOException("${archive.name} is not a .zip or .tar.gz")
        }
    }

    private class Budget { var left = MAX_BYTES }

    /** The file for [entry] under [root], or an [IOException] when it would escape it (zip-slip, absolute path, `..`). */
    internal fun safeTarget(root: File, entry: String): File {
        val clean = entry.replace('\\', '/')
        if (clean.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(clean) || clean.split('/').any { it == ".." }) {
            throw IOException("archive entry '$entry' escapes the install folder")
        }
        val base = root.canonicalFile
        val target = File(base, clean).canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator)) throw IOException("archive entry '$entry' escapes the install folder")
        return target
    }

    private fun copyLimited(input: InputStream, out: File, budget: Budget) {
        out.parentFile.mkdirs()
        out.outputStream().use { o ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                budget.left -= n
                if (budget.left < 0) throw IOException("archive is larger than $MAX_BYTES bytes unpacked")
                o.write(buffer, 0, n)
            }
        }
    }

    private fun unzip(raw: InputStream, into: File) {
        val budget = Budget()
        ZipInputStream(raw).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = safeTarget(into, entry.name)
                if (entry.isDirectory) target.mkdirs() else copyLimited(zip, target, budget)
            }
        }
    }

    private fun untar(raw: InputStream, into: File) {
        val budget = Budget()
        val input = DataInputStream(raw)
        val header = ByteArray(512)
        var longName: String? = null
        var paxPath: String? = null
        while (true) {
            try {
                input.readFully(header)
            } catch (_: EOFException) {
                break
            }
            if (header.all { it == 0.toByte() }) break
            var name = field(header, 0, 100)
            val prefix = field(header, 345, 155)
            if (prefix.isNotEmpty() && String(header, 257, 5, Charsets.US_ASCII) == "ustar") name = "$prefix/$name"
            val mode = octal(header, 100, 8).toInt()
            val size = readSize(header)
            val type = header[156].toInt().toChar()
            val padded = (size + 511) / 512 * 512
            when (type) {
                'L' -> {
                    longName = String(readMeta(input, size, padded), Charsets.UTF_8).trimEnd('\u0000')
                    continue
                }
                'x' -> {
                    paxPath = paxField(readMeta(input, size, padded), "path") ?: paxPath
                    continue
                }
                'g' -> {
                    skip(input, padded)
                    continue
                }
                else -> Unit
            }
            val entryName = (paxPath ?: longName ?: name).also { longName = null; paxPath = null }
            when (type) {
                '0', '\u0000', '7' -> {
                    val target = safeTarget(into, entryName)
                    copyLimited(BoundedStream(input, size), target, budget)
                    skip(input, padded - size)
                    if (mode and 0b001_001_001 != 0) target.setExecutable(true, false)
                }
                '5' -> safeTarget(into, entryName).mkdirs()
                '1', '2' -> throw IOException("archive entry '$entryName' is a link; links are refused")
                else -> skip(input, padded) // device nodes and the like: not part of a server download
            }
        }
    }

    private class BoundedStream(private val src: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else src.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    private fun field(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end] != 0.toByte()) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    private fun octal(h: ByteArray, off: Int, len: Int): Long =
        field(h, off, len).trim().ifEmpty { "0" }.toLongOrNull(8) ?: throw IOException("bad tar header")

    private fun readSize(h: ByteArray): Long {
        if (h[124].toInt() and 0x80 != 0) { // GNU base-256 size for entries over 8 GiB
            var v = (h[124].toInt() and 0x7f).toLong()
            for (i in 125 until 136) v = (v shl 8) or (h[i].toLong() and 0xff)
            return v
        }
        return octal(h, 124, 12)
    }

    private fun readMeta(input: DataInputStream, size: Long, padded: Long): ByteArray {
        if (size > 1024 * 1024) throw IOException("tar metadata entry too large")
        val data = ByteArray(size.toInt())
        input.readFully(data)
        skip(input, padded - size)
        return data
    }

    private fun skip(input: DataInputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                if (input.read() < 0) throw EOFException()
                left--
            } else left -= s
        }
    }

    private fun paxField(data: ByteArray, key: String): String? =
        String(data, Charsets.UTF_8).lineSequence().firstNotNullOfOrNull { line ->
            line.substringAfter(' ', "").takeIf { it.startsWith("$key=") }?.substringAfter('=')
        }
}
