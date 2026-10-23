package com.superiorsoftllc.agenthq.provider.providerconfig

import java.io.File
import java.nio.file.Files

/**
 * A throwaway "home" holding the config files the readers parse. Tests build every location from
 * [root]; nothing here (or in the readers it feeds) touches the real home directory.
 */
class ConfigFixture : AutoCloseable {
    val root: File = Files.createTempDirectory("agenthq-cp1-config").toFile()
    val opencodeConfig = File(root, "oc-config/opencode.jsonc")
    val opencodeAuth = File(root, "oc-data/auth.json")
    val opencodeModels = File(root, "oc-cache/models.json")
    val claudeSettings = File(root, "claude/settings.json")

    /** Rewrites [file] and bumps its mtime so the readers' size/mtime memo can tell it changed. */
    fun write(file: File, text: String) {
        file.parentFile.mkdirs()
        val before = if (file.isFile) file.lastModified() else 0L
        file.writeText(text)
        file.setLastModified(maxOf(before + 5_000, System.currentTimeMillis()))
    }

    /** Seeds the three opencode files with the shared sample content. */
    fun seedOpencode() {
        write(opencodeModels, MODELS_JSON)
        write(opencodeAuth, AUTH_JSON)
        write(opencodeConfig, CONFIG_JSONC)
    }

    fun opencodeLocations(env: Map<String, String> = emptyMap()) = OpenCodeConfigLocations(
        configFiles = listOf(opencodeConfig),
        authFile = opencodeAuth,
        modelsFile = opencodeModels,
        env = env,
    )

    fun claudeLocations(env: Map<String, String> = emptyMap()) =
        ClaudeConfigLocations(listOf(claudeSettings), env)

    override fun close() {
        root.deleteRecursively()
    }

    companion object {
        /** models.dev-shaped cache: `opencode-go` (one deprecated model), `acme` (needs ACME_KEY), `opencode`. */
        const val MODELS_JSON = """{
          "opencode-go": {"id":"opencode-go","name":"OpenCode Go","env":["OPENCODE_API_KEY"],"models":{
            "kimi-go-1":{"id":"kimi-go-1","name":"Kimi Go 1"},
            "glm-go-2":{"id":"glm-go-2","name":"GLM Go 2"},
            "old-go-0":{"id":"old-go-0","name":"Old","status":"deprecated"}}},
          "acme": {"id":"acme","name":"Acme AI","env":["ACME_KEY"],"models":{"acme-1":{"id":"acme-1"}}},
          "opencode": {"id":"opencode","name":"OpenCode","env":["OPENCODE_API_KEY"],"models":{"free-1":{"id":"free-1"}}}
        }"""

        const val AUTH_JSON = """{"opencode-go":{"type":"api","key":"not-a-real-key"}}"""

        /** JSONC on purpose: comments, a `//` inside a string, and trailing commas. */
        const val CONFIG_JSONC = """{
          // a comment the parser must survive
          "model": "opencode-go/kimi-go-1",
          "provider": {
            "homebrew": {
              "name": "Home Brew",
              "api": "https://example.invalid/v1", // a trailing comment after a string holding //
              "models": { "brew-1": {}, "brew-2": {}, },
            },
          },
        }"""

        const val CLAUDE_SETTINGS = """{"model":"claude-fixture-9","modelSettings":{"claude-fixture-8":{"effortLevel":"low"}}}"""
    }
}
