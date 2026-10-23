package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.claude.ClaudeSessionAdapter
import com.superiorsoftllc.agenthq.daemon.loadHarnesses
import com.superiorsoftllc.agenthq.harness.claude.ClaudeHarness
import com.superiorsoftllc.agenthq.harness.descriptor.DescriptorHarness
import com.superiorsoftllc.agenthq.harness.descriptor.HarnessDescriptorLoader
import com.superiorsoftllc.agenthq.harness.pi.PiHarness
import com.superiorsoftllc.agenthq.harness.pi.PiSessionAdapter
import com.superiorsoftllc.agenthq.opencode.OpenCodePermissions
import com.superiorsoftllc.agenthq.opencode.OpenCodeServeAdapter
import com.superiorsoftllc.agenthq.protocol.EventNormalizer
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.ClaudeCodeProvider
import com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs
import com.superiorsoftllc.agenthq.provider.HarnessProvider
import com.superiorsoftllc.agenthq.provider.OpenCodeProvider
import com.superiorsoftllc.agenthq.provider.ProviderFactory
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.session.host.AdapterSink
import com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter
import com.superiorsoftllc.agenthq.session.host.TurnEnd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * BUG-PERM-4: Tool approval across every harness x provider x model x mode.
 *
 * Enumerates what a spawn can resolve to, the way [com.superiorsoftllc.agenthq.session.SessionManager] does: every
 * [ProviderFactory] name (a configured provider), then every harness the daemon registers ([loadHarnesses]: the
 * ServiceLoader plugins plus the shipped sample descriptors in `docs/harnesses/` plus, when present, this machine's
 * own `$AGENTHQ_HOME/harnesses/`), each seen through [HarnessProvider]. For each one it takes every model its catalog
 * offers (the provider's `models()`, or [HarnessModelCatalogs] for a harness), plus "no model" and a probe id, and for
 * each of the three [ToolApproval] modes builds the one-shot spec AND, when the provider has one, launches its persistent
 * [HarnessSessionAdapter] against a capturing runner. Each is checked against that harness's mapping below.
 *
 * The oracle is by IMPLEMENTATION CLASS, never by id: a provider or harness this file does not know is a failure
 * ("unmapped"), so a future harness cannot silently fall back to deny.
 */
class ToolApprovalMatrixTest {

    /** One checked combination, for the report. */
    private data class Cell(val target: String, val via: String, val kind: String, val path: String, val model: String?, val mode: ToolApproval, val problem: String?)

    private val cells = CopyOnWriteArrayList<Cell>()

    @Test
    fun everyProviderAndHarnessCarriesItsMappingForEveryModelAndMode() {
        val registry = loadHarnesses(descriptorDir = SAMPLES_DIR, onNote = { fail("sample descriptor ${it.source} refused: ${it.reason} ${it.detail}") })
        machineDescriptorDir()?.let { dir ->
            HarnessDescriptorLoader(dir).loadInto(registry).notes.forEach { println("[matrix] machine descriptor ${it.source}: ${it.reason} ${it.detail}") }
        }
        assertTrue(registry.harnessIds().containsAll(listOf("claude", "pi", "codex", "gemini")), "registered: ${registry.harnessIds()}")

        // 1. every configured provider name, as the daemon resolves it first.
        for (id in ProviderFactory.ids()) {
            val provider = ProviderFactory.fromFlag(id)
            check(id, "provider", provider, providerModels(provider))
        }
        // 2. every registered harness, through the same adapter the daemon wraps it in (WIRE-4).
        val catalogs = HarnessModelCatalogs.builtIn().rows(registry.harnessIds(), covered = emptySet()) { id ->
            (registry.resolve(id) as? DescriptorHarness)?.modelCatalog
        }.groupBy({ it.provider }, { it.models })
        for (id in registry.harnessIds()) {
            val harness = registry.resolve(id)
            if (harness !is CliHarness) {
                // SessionManager.lookup only adopts a CliHarness: anything else refuses the spawn, so it has no combination
                // to check. (On the test classpath this is ServiceLoaderTestPlugin's in-memory fixture.)
                println("[matrix] $id: ${harness?.javaClass?.name} is not a CliHarness; a spawn naming it is refused, nothing to map")
                continue
            }
            check(id, "harness", HarnessProvider(harness), catalogs[id].orEmpty().flatten(), harness)
        }

        writeReport()
        val failures = cells.filter { it.problem != null }
        assertTrue(cells.size > 100, "the matrix checked only ${cells.size} cells")
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${cells.size} Tool approval cells failed:\n" + failures.take(40).joinToString("\n") {
                "  ${it.target} via ${it.via} [${it.kind}/${it.path}] model=${it.model ?: "(default)"} mode=${it.mode.wire}: ${it.problem}"
            })
        }
    }

    // --- the per-harness mapping (the oracle) ---------------------------------------------------------------------

    private fun check(id: String, via: String, provider: AgentProvider, offered: List<String>, harness: Harness? = null) {
        val kind = kindOf(provider, harness)
        val models = (listOf<String?>(null, PROBE_MODEL) + offered.distinct() + if (kind == "opencode") listOf(LUNA_6) else emptyList()).distinct()
        for (model in models) {
            val oneShot = ToolApproval.entries.associateWith { mode -> runCatching { provider.spec(request(model, mode)) } }
            for (mode in ToolApproval.entries) {
                val spec = oneShot.getValue(mode).getOrElse { record(id, via, kind, "one-shot", model, mode, "spec threw ${it::class.simpleName}: ${it.message}"); null } ?: continue
                record(id, via, kind, "one-shot", model, mode, oneShotProblem(kind, harness, spec, oneShot.getValue(ToolApproval.DENY).getOrNull(), mode))
            }
            provider.sessionAdapter()?.let { adapter -> for (mode in ToolApproval.entries) record(id, via, kind, "session:${adapter.label}", model, mode, sessionProblem(kind, adapter, model, mode)) }
        }
    }

    private fun kindOf(provider: AgentProvider, harness: Harness?): String = when {
        harness is ClaudeHarness || provider is ClaudeCodeProvider -> "claude"
        harness is PiHarness -> "pi"
        harness is DescriptorHarness -> "descriptor"
        provider is OpenCodeProvider -> "opencode"
        provider is StubProvider -> "stub"
        else -> "unmapped"
    }

    /** Null when [spec] carries [kind]'s mapping for [mode]. */
    private fun oneShotProblem(kind: String, harness: Harness?, spec: ProcessSpec, deny: ProcessSpec?, mode: ToolApproval): String? = when (kind) {
        "claude" -> claudeProblem(spec.command, mode)
        "opencode" -> {
            val auto = "--auto" in spec.command
            val cfg = spec.env[OpenCodePermissions.CONFIG_CONTENT_ENV]
            // BUG-PERM-5: every mode carries `bash."agenthq *" = allow` (AgentHQ's own CLI), and nothing else widens deny.
            if (!agentHqAllowed(cfg)) "every mode must allow bash \"agenthq *\" in ${OpenCodePermissions.CONFIG_CONTENT_ENV}, got $cfg"
            else when (mode) {
                // PROMPT-1: no harness can prompt yet, so `ask` must be exactly `deny`.
                ToolApproval.DENY, ToolApproval.ASK -> if (auto) "deny must not add --auto" else if (editAllowed(cfg)) "deny must not allow edit, got $cfg" else null
                ToolApproval.ALLOW_EDITS -> if (auto) "allowEdits must not add --auto" else if (!editAllowed(cfg)) "allowEdits must set permission.edit=allow, got $cfg" else null
                ToolApproval.ALLOW_ALL -> if (!auto) "allowAll must add --auto" else if (editAllowed(cfg)) "allowAll must not set permission.edit (it has --auto), got $cfg" else null
            }
        }
        // PM decision (BUG-PERM-3): pi has no approval prompt, all its tools run in every mode, so no mode adds a flag.
        "pi" -> piProblem(spec.command, deny?.command)
        "descriptor" -> descriptorProblem((harness as DescriptorHarness), spec.command, deny?.command, mode)
        "stub" -> null // a scripted replay with no tools at all: there is nothing to approve.
        else -> "unmapped harness/provider: no Tool approval mapping is known for it"
    }

    private fun claudeProblem(command: List<String>, mode: ToolApproval): String? {
        val at = command.indexOf("--permission-mode")
        val actual = if (at < 0) null else command.getOrNull(at + 1)
        val expected = when (mode) {
            ToolApproval.DENY, ToolApproval.ASK -> null // PROMPT-1: Claude `-p` cannot prompt; ask = deny
            ToolApproval.ALLOW_EDITS -> "acceptEdits"
            ToolApproval.ALLOW_ALL -> "bypassPermissions"
        }
        if (actual != expected) return "--permission-mode expected $expected, got $actual"
        // BUG-PERM-5: the agenthq CLI is AgentHQ's own control plane, allowed in EVERY mode (deny included).
        val allowed = command.indexOf("--allowedTools").takeIf { it >= 0 }?.let { command.getOrNull(it + 1) }
            ?.split(',')?.map { it.trim() }.orEmpty()
        return if (allowed.containsAll(CLAUDE_AGENTHQ_RULES)) null else "--allowedTools must carry $CLAUDE_AGENTHQ_RULES, got $allowed"
    }

    private fun piProblem(command: List<String>, deny: List<String>?): String? = when {
        deny != null && command != deny -> "pi must run the same argv in every mode (all tools run), differs from deny: $command"
        command.any { it in PI_TOOL_FLAGS } -> "pi argv names a tool-restricting flag: $command"
        else -> null
    }

    private fun descriptorProblem(harness: DescriptorHarness, command: List<String>, deny: List<String>?, mode: ToolApproval): String? {
        val map = harness.descriptor.toolApproval ?: return "descriptor declares no 'toolApproval' map: every mode silently keeps the CLI's default"
        val mode = mode.withoutPrompt() // PROMPT-1: a descriptor cannot prompt; ask = deny
        val tokens = map[mode]
        if (mode != ToolApproval.DENY && tokens.isNullOrEmpty()) return "descriptor maps no '${mode.wire}' argv"
        if (!tokens.isNullOrEmpty() && Collections.indexOfSubList(command, tokens) < 0) return "argv lacks the mapped $tokens: $command"
        if (mode == ToolApproval.DENY && deny != null) {
            val all = map[ToolApproval.ALLOW_ALL].orEmpty()
            if (all.isNotEmpty() && Collections.indexOfSubList(command, all) >= 0) return "deny argv carries the allowAll tokens $all"
        }
        return null
    }

    /** The persistent mode, launched for real against a capturing runner (no process starts). */
    private fun sessionProblem(kind: String, adapter: HarnessSessionAdapter, model: String?, mode: ToolApproval): String? {
        if (adapter is OpenCodeServeAdapter) {
            // serve answers each ask itself; edit is the only permission allowEdits lets through.
            val expected = mapOf(
                "edit" to (mode.withoutPrompt() != ToolApproval.DENY),
                "bash" to (mode == ToolApproval.ALLOW_ALL),
            )
            // BUG-PERM-5: a bash ask for the agenthq CLI is answered `once` in every mode; `git status` follows the mode.
            val agentHq = buildJsonObject { put("id", "per_matrix"); put("permission", "bash"); put("patterns", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("agenthq ticket create --title x")))) }
            val a = OpenCodeServeAdapter.permissionDecision(agentHq, mode) ?: return "no decision for agenthq"
            if ((Json.parseToJsonElement(a.replyBody).jsonObject["reply"] as JsonPrimitive).content != "once" || a.deniedTool != null)
                return "serve must answer an agenthq bash ask with once in ${mode.wire}, got ${a.replyBody}"
            return expected.entries.firstNotNullOfOrNull { (permission, allowed) ->
                val props = buildJsonObject { put("id", "per_matrix"); put("permission", permission); if (permission == "bash") put("patterns", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("git status")))) }
                val d = OpenCodeServeAdapter.permissionDecision(props, mode) ?: return@firstNotNullOfOrNull "no decision for $permission"
                val reply = (Json.parseToJsonElement(d.replyBody).jsonObject["reply"] as JsonPrimitive).content
                val ok = if (allowed) reply == "once" && d.deniedTool == null else reply == "reject" && d.deniedTool == permission
                if (ok) null else "serve answers '$permission' with $reply (denied=${d.deniedTool}), expected ${if (allowed) "once" else "reject + denial"}"
            }
        }
        if (adapter !is ClaudeSessionAdapter && adapter !is PiSessionAdapter) return "unmapped session adapter ${adapter.javaClass.name} (label ${adapter.label})"
        val launched = launch(adapter, model, mode).getOrElse { return "launch threw ${it::class.simpleName}: ${it.message}" }
        return when (kind) {
            "claude" -> claudeProblem(launched, mode)
            "pi" -> piProblem(launched, launch(adapter, model, ToolApproval.DENY).getOrNull())
            else -> "session adapter ${adapter.label} on a $kind target"
        }
    }

    private fun launch(adapter: HarnessSessionAdapter, model: String?, mode: ToolApproval): Result<List<String>> = runCatching {
        val runner = CapturingRunner()
        val process = adapter.launch(request(model, mode), emptyMap(), runner, NullSink)
        runCatching { adapter.close(process) }
        runner.specs.single().command
    }

    // --- carried over from PERM-2 / PERM-3 ----------------------------------------------------------------------

    @Test
    fun openCodeAllowEditsMergesIntoTheUsersInlineConfigInsteadOfReplacingIt() {
        val merged = Json.parseToJsonElement(OpenCodePermissions.mergeAllowEdits("""{"model":"x/y","permission":{"bash":"ask"}}""")).jsonObject
        assertEquals("\"x/y\"", merged["model"].toString())
        assertEquals("""{"bash":"ask","edit":"allow"}""", merged["permission"].toString())
        // The single-string shorthand keeps its meaning as the fallback rule.
        assertEquals("""{"*":"deny","edit":"allow"}""", Json.parseToJsonElement(OpenCodePermissions.mergeAllowEdits("""{"permission":"deny"}""")).jsonObject["permission"].toString())
        // Nothing usable to merge into: ours alone.
        for (bad in listOf(null, "", "  ", "not json", "[1]")) assertEquals(OpenCodePermissions.ALLOW_EDITS_CONFIG, OpenCodePermissions.mergeAllowEdits(bad))
        assertEquals(
            mapOf(OpenCodePermissions.CONFIG_CONTENT_ENV to OpenCodePermissions.withAgentHqMcp("""{"username":"u","permission":{"edit":"allow","bash":{"agenthq *":"allow"}}}""", emptyMap())), // MCP-1
            OpenCodePermissions.runEnv(ToolApproval.ALLOW_EDITS, existing = """{"username":"u"}"""),
        )
        // BUG-PERM-5: deny merges the agenthq allow only, and keeps the user's keys.
        assertEquals(
            mapOf(OpenCodePermissions.CONFIG_CONTENT_ENV to OpenCodePermissions.withAgentHqMcp("""{"username":"u","permission":{"bash":{"agenthq *":"allow"}}}""", emptyMap())), // MCP-1
            OpenCodePermissions.runEnv(ToolApproval.DENY, existing = """{"username":"u"}"""),
        )
    }

    @Test
    fun anOpenCodeRunAutoRejectBecomesTheDenialNoticeOnce() {
        // Verbatim shape from opencode 1.18.35 `run --format json` with `permission.bash = ask` (timestamps/ids shortened).
        val rejected = """{"type":"tool_use","timestamp":1,"sessionID":"ses_1","part":{"type":"tool","tool":"bash","callID":"c1","state":{"status":"error","input":{"command":"echo PERMPROBE"},"error":"The user rejected permission to use this specific tool call.","time":{"start":1,"end":2}}}}"""
        assertEquals(
            listOf(EventNormalizer.Line.Denial("opencode", "bash")),
            EventNormalizer.normalise(rejected).filterIsInstance<EventNormalizer.Line.Denial>(),
        )
        // The serve adapter already reported its own reject: the forwarded part must not announce it again.
        val forwarded = (Json.parseToJsonElement(rejected) as JsonObject).let { JsonObject(it + (EventNormalizer.OPENCODE_DENIAL_REPORTED to JsonPrimitive(true))) }.toString()
        assertEquals(emptyList(), EventNormalizer.normalise(forwarded).filterIsInstance<EventNormalizer.Line.Denial>())
        // An ordinary failed command is not a denial.
        val failed = rejected.replace("The user rejected permission to use this specific tool call.", "exit status 1")
        assertEquals(emptyList(), EventNormalizer.normalise(failed).filterIsInstance<EventNormalizer.Line.Denial>())
    }

    /**
     * [com.superiorsoftllc.agenthq.provider.ConfigProvider] (PROV-1) has no Tool approval mapping. It is safe only while
     * nothing in the daemon wires [com.superiorsoftllc.agenthq.provider.ConfigProviderLoader]; the day something does,
     * this fails so the mapping is added in the same change.
     */
    @Test
    fun configDefinedProvidersStayUnwiredUntilTheyMapToolApproval() {
        val main = File("src/main/kotlin")
        assertTrue(main.isDirectory, "run from the agentHq module dir (cwd ${File(".").absolutePath})")
        val wiring = main.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "ConfigProviderLoader.kt" }
            .filter { it.readText().contains("ConfigProviderLoader(") }.map { it.name }.toList()
        assertEquals(emptyList(), wiring, "ConfigProviderLoader is wired; give ConfigProvider a toolApproval mapping and add it to ToolApprovalMatrixTest")
    }

    // --- plumbing -------------------------------------------------------------------------------------------------

    private fun request(model: String?, mode: ToolApproval) = RunRequest(prompt = "matrix", workingDir = File("."), model = model, toolApproval = mode)

    private fun providerModels(provider: AgentProvider): List<String> =
        runCatching { provider.models().flatMap { it.models } }.getOrElse { println("[matrix] ${provider.id} models() threw: ${it.message}"); emptyList() }

    private fun editAllowed(config: String?): Boolean = runCatching {
        ((Json.parseToJsonElement(config!!).jsonObject["permission"] as JsonObject)["edit"] as JsonPrimitive).content == "allow"
    }.getOrDefault(false)

    private fun agentHqAllowed(config: String?): Boolean = runCatching {
        val bash = (Json.parseToJsonElement(config!!).jsonObject["permission"] as JsonObject)["bash"] as JsonObject
        (bash["agenthq *"] as JsonPrimitive).content == "allow"
    }.getOrDefault(false)

    private fun record(target: String, via: String, kind: String, path: String, model: String?, mode: ToolApproval, problem: String?) {
        cells += Cell(target, via, kind, path, model, mode, problem)
    }

    /** One line per target / path / mode, with the model count, into build/reports for the DONE report. */
    private fun writeReport() {
        val rows = cells.groupBy { listOf(it.target, it.via, it.kind, it.path, it.mode.wire) }.map { (k, group) ->
            val bad = group.count { it.problem != null }
            "| ${k[0]} | ${k[1]} | ${k[2]} | ${k[3]} | ${k[4]} | ${group.size} | ${if (bad == 0) "PASS" else "FAIL ($bad)"} |"
        }
        val text = buildString {
            appendLine("| target | via | harness kind | path | mode | models checked | result |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- |")
            rows.forEach(::appendLine)
            appendLine()
            appendLine("cells: ${cells.size}, failed: ${cells.count { it.problem != null }}")
        }
        File("build/reports").mkdirs()
        File("build/reports/tool-approval-matrix.md").writeText(text)
        println(text)
    }

    private fun machineDescriptorDir(): File? =
        (System.getenv("AGENTHQ_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: System.getenv("AGENTHQ_STATE_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".agenthq")).resolve("harnesses").takeIf { it.isDirectory }

    private class CapturingRunner : ProcessRunner {
        override val id = "matrix-capture"
        val specs = CopyOnWriteArrayList<ProcessSpec>()
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            return object : RunningProcess {
                @Volatile var alive = true
                override val isAlive get() = alive
                override fun writeInput(text: String) = Unit
                override fun closeInput() = Unit
                override fun killTree() { alive = false }
                override fun awaitExit() = 0
            }
        }
    }

    private object NullSink : AdapterSink {
        override fun output(line: String) = Unit
        override fun diagnostic(line: String) = Unit
        override fun detail(text: String) = Unit
        override fun providerSession(id: String) = Unit
        override fun turnEnded(end: TurnEnd, exitCode: Int?, reason: String?) = Unit
    }

    private companion object {
        val SAMPLES_DIR = File("docs/harnesses")
        const val PROBE_MODEL = "matrix/probe-model"

        /** The demo model the owner named for OpenCode (Luna 6). */
        const val LUNA_6 = "opencode-go/gpt-6-luna"
        /** BUG-PERM-5: verified live against claude 2.1.304 (`-p --allowedTools`). */
        val CLAUDE_AGENTHQ_RULES = listOf("Bash(agenthq:*)", "PowerShell(agenthq:*)")
        val PI_TOOL_FLAGS = setOf("--no-tools", "--tools", "--exclude-tools")
    }
}
