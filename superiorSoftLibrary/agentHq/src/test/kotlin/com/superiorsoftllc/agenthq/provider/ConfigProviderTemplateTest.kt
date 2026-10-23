package com.superiorsoftllc.agenthq.provider

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PROV-2: a JSON provider's [args] are an argv TEMPLATE, not literals.
 *
 * Where PROV-1 proved the definition lives in DATA, PROV-2 proves the DATA can name a
 * per-run value: `{model}`, `{session}`, `{workdir}` resolve from the [RunRequest], so one
 * `aider.json` (or `opencode.json`) serves fresh and resume runs without a recompile.
 *
 * The rule that makes a template usable on a real CLI: a switch whose value is absent must
 * vanish WITH its flag, not leave a dangling `--session` the CLI then reads the next arg as
 * (or errors on). So a null `model`/`session` drops `-m {model}` / `--session {session}`
 * together. One test, one file, argv read straight from the JSON through the same seam the
 * daemon uses.
 */
class ConfigProviderTemplateTest {

    @Test
    fun jsonProviderFillsArgvTemplate() {
        val dir: File = Files.createTempDirectory("agenthq-prov2-").toFile()
        File(dir, "templated.json").writeText(
            """{"id":"templated-fake","command":"fake.exe",""" +
                """"args":["run","-m","{model}","--session","{session}","--dir","{workdir}"]}""",
        )

        ConfigProviderLoader(dir).load()
        val provider = ProviderFactory.fromFlag("templated-fake")

        val workDir = File("work-dir")

        val full = provider.spec(
            RunRequest(
                prompt = "hi",
                workingDir = workDir,
                resumeSessionId = "sess-1",
                model = "model-1",
            ),
        )
        assertEquals(
            listOf("fake.exe", "run", "-m", "model-1", "--session", "sess-1", "--dir", workDir.path),
            full.command,
        )

        val bare = provider.spec(
            RunRequest(prompt = "hi", workingDir = workDir, resumeSessionId = null, model = null),
        )
        assertEquals(
            listOf("fake.exe", "run", "--dir", workDir.path),
            bare.command,
        )
    }
}
