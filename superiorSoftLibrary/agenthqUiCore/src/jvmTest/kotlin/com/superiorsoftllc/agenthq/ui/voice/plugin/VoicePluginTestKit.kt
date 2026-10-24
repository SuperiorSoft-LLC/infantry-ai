package com.superiorsoftllc.agenthq.ui.voice.plugin

import com.superiorsoftllc.agenthq.ui.voice.Speaker
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest

/** VOICE-PLUGIN-1: shared fixture: a temp plugins dir, a fake plugin (a real child JVM) and an offline downloader. Never the network. */
abstract class VoicePluginTestKit {
    protected val root: File = Files.createTempDirectory("ahq-voice-plugins").toFile()
    protected val platform = "windows-x64"

    @AfterTest fun cleanUp() { root.deleteRecursively() }

    protected val voiceBytes = ByteArray(1000) { it.toByte() }
    protected val zipBytes: ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("bin/")); z.closeEntry()
            z.putNextEntry(ZipEntry("bin/readme.txt")); z.write("hi".toByteArray()); z.closeEntry()
        }
    }.toByteArray()

    protected fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val java = ProcessHandle.current().info().command().get()
    private val classpath = listOf(FakeTts::class.java, Unit::class.java)
        .joinToString(File.pathSeparator) { File(it.protectionDomain.codeSource.location.toURI()).path }

    protected fun manifest(mode: String, id: String = "fake-$mode", api: String = VOICE_PLUGIN_API, timeoutMs: Long = 10_000, voiceSha: String = sha(voiceBytes)) =
        VoicePluginManifest(
            id = id, name = "Fake $mode", version = "1.0.0", kind = "tts", pluginApi = api, platforms = listOf(platform),
            entry = listOf(java, "-Xmx32m", "-cp", classpath, FakeTts::class.java.name, mode),
            output = VoicePluginOutput(sampleRate = 16_000), timeoutMs = timeoutMs, license = "test",
            assets = listOf(
                VoicePluginAsset("tools.zip", "https://example.invalid/tools.zip", zipBytes.size.toLong(), sha(zipBytes), unpack = true),
                VoicePluginAsset("voice.bin", "https://example.invalid/voice.bin", voiceBytes.size.toLong(), voiceSha),
            ),
        )

    protected val downloads = ArrayList<String>()
    protected val downloader = AssetDownloader { url, target, max, _ ->
        downloads += url
        val body = when { url.endsWith("tools.zip") -> zipBytes; url.endsWith("voice.bin") -> voiceBytes; else -> throw IOException("404 $url") }
        if (body.size > max) throw IOException("too big")
        target.writeBytes(body)
    }

    protected fun store(vararg catalog: VoicePluginManifest) = VoicePluginStore(root, catalog.toList(), platform, downloader)

    class RecordingSpeaker : Speaker {
        val spoken: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun speak(text: String, volumePercent: Int, voiceName: String?, interrupt: Boolean): Boolean { spoken += text; return true }
        override fun beep() {}
    }

    /** Whatever the test JVM already runs (Gradle's own helpers) is not a plugin process. */
    private val baseline: Set<Long> = ProcessHandle.current().descendants().map { it.pid() }.toList().toSet()
    protected fun livePluginProcesses() = ProcessHandle.current().descendants().filter { it.isAlive && it.pid() !in baseline }.count()
}
