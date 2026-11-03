package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import com.superiorsoftllc.infantry.harness.api.ContextLimitCapability
import com.superiorsoftllc.infantry.harness.api.ContextLimitSupport
import com.superiorsoftllc.infantry.harness.api.EffortCapability
import com.superiorsoftllc.infantry.harness.api.EffortSupport
import com.superiorsoftllc.infantry.harness.api.HarnessCommandAction
import com.superiorsoftllc.infantry.harness.api.HarnessCommandSpec
import com.superiorsoftllc.infantry.harness.api.HarnessModels
import com.superiorsoftllc.infantry.harness.api.ToolApproval

/**
 * HARNESS H8b-2: the `opencode run` invocation (the core `OpenCodeCommand`, on the plugin). Two Phase 0 facts stay encoded and must not be
 * "simplified": the NATIVE `opencode.exe` is spawned (the host's CLI resolver finds it), and the prompt goes to stdin which is then CLOSED
 * (`opencode run` blocks on stdin EOF). `--format json` gives one JSON object per line, empty stderr, no ANSI.
 */
object OpenCodeCommand {
    const val HARNESS_ID = "opencode"
    const val CLI_ID = "opencode"

    /** Free model: the daemon must never burn a metered model by default. */
    const val DEFAULT_MODEL = "opencode/deepseek-v4.1-flash-free"

    const val MODEL_ENV = "INFANTRY_OPENCODE_MODEL"

    /** `INFANTRY_OPENCODE_MODEL`, else [DEFAULT_MODEL]. A blank or "default" value is not a model id (BUG-G2). */
    fun model(env: (String) -> String? = System::getenv): String = env(MODEL_ENV)?.let(::realModel) ?: DEFAULT_MODEL

    fun realModel(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && !it.equals("default", ignoreCase = true) }

    /** The session's explicit choice first, then `INFANTRY_OPENCODE_MODEL`, then [DEFAULT_MODEL]. */
    fun effectiveModel(requested: String?, env: (String) -> String? = System::getenv): String = realModel(requested) ?: model(env)

    /** Pure argv; the permission flags ([OpenCodePermissions.runFlags]) are NOT here: the host appends [approvalArgs]. */
    fun command(executable: String, model: String, sessionId: String?): List<String> = buildList {
        add(executable)
        add("run")
        add("--format"); add("json")
        add("-m"); add(model)
        sessionId?.takeIf { it.isNotBlank() }?.let { add("--session"); add(it) }
    }

    fun approvalArgs(approval: ToolApproval): List<String> = OpenCodePermissions.runFlags(approval)

    /** Verified on opencode 1.18.35: a window is only settable through a full per-model limit override (context + output). */
    val CONTEXT_LIMIT = ContextLimitCapability(
        ContextLimitSupport.UNSUPPORTED,
        "opencode sets its window only through a full per-model limit override (context + output); not mapped yet",
    )

    val EFFORT = EffortCapability(
        EffortSupport.UNSUPPORTED,
        "opencode has only a provider-specific --variant flag, not verified end to end; not mapped yet",
    )

    /** STATIC: the opencode CLI cannot enumerate its slash commands. `/model` switches the next run's model; `/compact` is opencode's own. */
    val COMMANDS: List<HarnessCommandSpec> = listOf(
        HarnessCommandSpec("model", "Switch the model for the next turn", HarnessCommandAction.SET_MODEL, argument = "<provider/model>"),
        HarnessCommandSpec("compact", "Compact the conversation (opencode's own /compact)", HarnessCommandAction.NATIVE, native = "/compact"),
    )

    fun isModelId(s: String): Boolean = s.isNotEmpty() && s.contains('/') && s.none(Char::isWhitespace)

    /** The picker rows for [lines] (`provider/model` ids), grouped by vendor; [notes] make a broken config visible. */
    fun models(lines: List<String>, notes: List<String>): List<HarnessModels> {
        val byVendor = LinkedHashMap<String, MutableList<String>>()
        for (line in lines.map { it.trim() }) {
            val vendor = line.substringBefore('/').takeIf { '/' in line && it.isNotEmpty() } ?: continue
            byVendor.getOrPut(vendor) { mutableListOf() }.add(line)
        }
        if (byVendor.isEmpty()) {
            val why = (listOf("opencode reported no configured models") + notes).joinToString("; ")
            return listOf(HarnessModels("", HARNESS_ID, emptyList(), why))
        }
        val groups = byVendor.map { (vendor, ids) -> HarnessModels(vendor, vendor, ids) }
        return if (notes.isEmpty()) groups else groups + HarnessModels("config", "config notes", emptyList(), notes.joinToString("; "))
    }
}
