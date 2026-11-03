package com.superiorsoftllc.infantry.ui.voice.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoicePluginManifestTest {
    private val sha = "a".repeat(64)
    private fun manifest(
        api: String = VOICE_PLUGIN_API,
        entry: String = """["{dir}/tts.exe"]""",
        assetPath: String = "voice.onnx",
        assetSha: String = sha,
        kind: String = "tts",
    ) = """{"id":"fake-tts","name":"Fake","version":"1.0.0","kind":"$kind","pluginApi":"$api","platforms":["windows-x64"],
        "entry":$entry,"output":{"format":"pcm-s16le","sampleRate":16000,"channels":1},"timeoutMs":5000,
        "assets":[{"path":"$assetPath","url":"https://example.invalid/v","size":10,"sha256":"$assetSha"}]}"""

    private fun problems(text: String, platform: String? = "windows-x64") =
        VoicePluginManifests.problems(VoicePluginManifests.parse(text).getOrThrow(), platform)

    @Test fun aGoodManifestParsesAndHasNoProblems() {
        val m = VoicePluginManifests.parse(manifest()).getOrThrow()
        assertEquals("fake-tts", m.id)
        assertEquals(16000, m.output.sampleRate)
        assertEquals(10, m.downloadBytes)
        assertEquals(emptyList(), VoicePluginManifests.problems(m, "windows-x64"))
    }

    @Test fun aWrongPluginApiIsRefusedExactMatchOnly() {
        assertTrue(problems(manifest(api = "1.0.1")).any { "plugin API 1.0.1" in it })
        assertTrue(problems(manifest(api = "2.0.0")).any { "plugin API" in it })
    }

    @Test fun aMissingEntryIsRefused() {
        assertTrue(problems(manifest(entry = "[]")).any { "entry is missing" in it })
        assertTrue(VoicePluginManifests.parse(manifest().replace(""""entry":["{dir}/tts.exe"],""", "")).isFailure, "entry is required")
    }

    @Test fun aMalformedChecksumIsRefused() {
        assertTrue(problems(manifest(assetSha = "1234")).any { "sha256" in it })
    }

    @Test fun anAssetCannotEscapeThePluginFolder() {
        for (bad in listOf("../x", "/etc/x", "C:/x", "a/../../x", "a\\\\b")) {
            assertTrue(problems(manifest(assetPath = bad)).any { "plain relative path" in it }, bad)
        }
        assertTrue(VoicePluginManifests.safeRelative("piper/piper.exe"))
    }

    @Test fun anotherPlatformOrKindIsRefused() {
        assertTrue(problems(manifest(), "linux-x64").any { "not built for linux-x64" in it })
        assertTrue(problems(manifest(kind = "stt")).any { "kind" in it })
    }

    @Test fun malformedJsonIsAFailureNotAnException() {
        assertTrue(VoicePluginManifests.parse("{not json").isFailure)
        assertTrue(VoicePluginManifests.parse("""{"id":"x"}""").isFailure)
    }

    @Test fun settingsWording() {
        val row = VoicePluginRow("p", "P", "", "", 85_581_931, "missing")
        assertEquals("Install (86 MB download)", PluginsSetting.installLabel(row))
        assertEquals("Not installed", PluginsSetting.statusText(row))
        assertEquals("Installed 1.0.0, in use", PluginsSetting.statusText(row.copy(state = "installed", installedVersion = "1.0.0", enabled = true)))
        assertTrue(PluginsSetting.statusText(row.copy(state = "broken", note = "bad")).contains("system voice"))
    }
}
