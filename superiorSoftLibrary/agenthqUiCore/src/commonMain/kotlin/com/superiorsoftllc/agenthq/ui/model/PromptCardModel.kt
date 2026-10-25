package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.PromptRequestView
import com.superiorsoftllc.agenthq.ui.protocol.PromptResponseWire

/**
 * PROMPT-2 (contract 1.77.0): the chat prompt card. A harness asks the owner something mid-run (may this tool run?
 * which of these options?); the daemon pushes a `prompt_request` to every client and the card docks above the chat
 * input of the session that asked. Pure: everything the card decides lives here so it is tested without a window.
 */
object PromptCards {
    const val KIND_TOOL_APPROVAL = "tool_approval"
    const val KIND_QUESTION = "question"

    const val ALLOW_ONCE = "allow_once"
    const val ALLOW_SESSION = "allow_session"
    const val DENY = "deny"

    /** PROMPT-6: "remember this tool (or this command prefix) from now on". Sent only to a daemon that takes it. */
    const val ALLOW_ALWAYS = "allow_always"

    /**
     * The first contract whose daemon takes [ALLOW_ALWAYS]. PROVISIONAL: PROMPT-6 was not merged when PROMPT-2 was
     * built, so this is the next minor after 1.77.0; PROMPT-6 must bump the contract to (at least) this value, or
     * correct it here. Below it the button is hidden, because a 1.77.0 daemon refuses the value.
     */
    const val ALLOW_ALWAYS_SINCE_MAJOR = 1
    const val ALLOW_ALWAYS_SINCE_MINOR = 78

    /** Whether the connected daemon takes [ALLOW_ALWAYS] (the capability check that shows or hides the button). */
    fun offersAllowAlways(contract: String?): Boolean = contractAtLeast(contract, ALLOW_ALWAYS_SINCE_MAJOR, ALLOW_ALWAYS_SINCE_MINOR)

    /** One button of a tool-approval card: what it says and the decision it sends. */
    data class Choice(val label: String, val decision: String)

    /** The approval buttons, in order. Allow once first: it is what Enter picks before an arrow key moves. */
    fun approvalChoices(prompt: PromptRequestView, allowAlways: Boolean): List<Choice> = buildList {
        add(Choice("Allow once", ALLOW_ONCE))
        add(Choice("Allow for this session", ALLOW_SESSION))
        if (allowAlways) add(Choice(alwaysAllowLabel(prompt.tool, prompt.input), ALLOW_ALWAYS))
        add(Choice("Deny", DENY))
    }

    private val SHELL_TOOLS = setOf("bash", "powershell", "pwsh", "shell", "cmd")

    /**
     * The "Always allow" label. A shell tool names the command prefix it will remember ("Always allow `git` commands");
     * any other tool names the tool ("Always allow Edit").
     */
    fun alwaysAllowLabel(tool: String?, input: String?): String {
        val name = tool?.trim().orEmpty()
        if (name.lowercase() in SHELL_TOOLS) {
            commandPrefix(input)?.let { return "Always allow `$it` commands" }
        }
        return if (name.isEmpty()) "Always allow" else "Always allow $name"
    }

    /** The first word of a shell command line, without quotes or a path ("C:\x\git.exe status" -> "git"). Null when blank. */
    fun commandPrefix(input: String?): String? {
        val line = input?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // A quoted program path may hold spaces ("C:\Program Files\Git\bin\git.exe" status): take up to the closing quote.
        val quote = line.first().takeIf { it == '"' || it == '\'' }
        val first = (if (quote != null) line.drop(1).substringBefore(quote) else line.split(Regex("\\s+")).first())
            .trim('"', '\'', '`').takeIf { it.isNotEmpty() } ?: return null
        val base = first.substringAfterLast('/').substringAfterLast('\\')
        return base.removeSuffix(".exe").removeSuffix(".EXE").takeIf { it.isNotEmpty() }
    }

    /** "1 of N" when more than one prompt waits for this session, else null. */
    fun position(count: Int): String? = if (count > 1) "1 of $count" else null

    /** The answer to a tool approval. */
    fun decide(prompt: PromptRequestView, decision: String): PromptResponseWire = PromptResponseWire(prompt.promptId, decision = decision)

    /**
     * The answer to a question: the chosen option labels in the prompt's order (at most one unless multiSelect), plus
     * the Other text when the prompt takes one and it is not blank. Null when there is nothing to send (the daemon
     * refuses an empty answer).
     */
    fun answer(prompt: PromptRequestView, chosen: Set<String>, otherText: String): PromptResponseWire? {
        val labels = prompt.options.map { it.label }.filter { it in chosen }
        val answers = if (prompt.multiSelect) labels else labels.take(1)
        val other = otherText.trim().takeIf { it.isNotEmpty() }
        if (answers.isEmpty() && other == null) return null
        return PromptResponseWire(prompt.promptId, answers = answers, otherText = other)
    }

    /** A question's selection after the owner picks [label]: radio replaces, checkbox toggles. */
    fun toggle(prompt: PromptRequestView, chosen: Set<String>, label: String): Set<String> = when {
        !prompt.multiSelect -> setOf(label)
        label in chosen -> chosen - label
        else -> chosen + label
    }

    /** Arrow-key movement over [size] rows, clamped (no wrap). */
    fun move(index: Int, delta: Int, size: Int): Int = if (size <= 0) 0 else (index + delta).coerceIn(0, size - 1)

    /**
     * What Esc does. A tool approval sends [DENY]. A question has no "no answer" on the wire (1.77.0 refuses an empty
     * `prompt_response`), so Esc only hides the card in this window: the prompt stays open on the daemon, which resolves
     * it as no answer at its timeout, and the session keeps its "needs you" badge. Null = send nothing.
     */
    fun escape(prompt: PromptRequestView): PromptResponseWire? =
        if (prompt.kind == KIND_TOOL_APPROVAL) decide(prompt, DENY) else null
}
