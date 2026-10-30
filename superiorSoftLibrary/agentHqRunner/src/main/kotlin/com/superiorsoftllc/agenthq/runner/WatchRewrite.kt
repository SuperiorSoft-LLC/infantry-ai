package com.superiorsoftllc.agenthq.runner

import java.util.Base64

/**
 * STALL-2 (ticket a46744ec) item 1: the ONE rule set the per-harness hooks ask before a shell tool call runs (claude
 * PreToolUse `http` hook, the opencode `tool.execute.before` plugin, the pi `tool_call` extension; docs/STALL.md). It
 * closes STALL-1's hole: a program named by a PATH (`./gradlew`, `.\gradlew.bat`, `gradlew.bat` from the cwd, `java -jar`)
 * never meets a PATH shim, so its end reached the worker only through the process-tree watch, without an exit code.
 *
 * A command is rewritten when one of its simple commands runs a [LONG] program (by its base name, path or not), `java -jar`,
 * or when it is put in the background (`&`, `nohup`, `setsid`, `Start-Process`, `Start-Job`, the harness's own
 * background flag). The whole command (not one word of it) becomes a script the runner executes with the same shell, so
 * pipes, redirections and `&&` chains keep their meaning; it travels base64url-encoded, so no quoting layer (bash, cmd.exe
 * of the `.cmd` launcher, PowerShell) can change it. A leading `cd <dir> &&` stays outside, so the harness still sees the
 * directory change.
 *
 *  - foreground: `agenthq bg run --attached --hook <harness> ...`: same console output and exit code, plus the runner's
 *    log, END sentinel and the daemon registration (the stall detector knows when it ended).
 *  - background: `agenthq bg run --notify --hook <harness> ...`: detached, returns at once, the daemon mails the end.
 *
 * Never rewritten: an `agenthq` call that already chose (`bg`, `run-watched`, `unwatched`, or any foreground one), and a
 * command with nothing long or backgrounded in it. The opt-out stays `agenthq unwatched --reason "<why>" -- <cmd...>`.
 */
object WatchRewrite {
    enum class Shell { BASH, POWERSHELL }

    data class Request(
        val command: String,
        val shell: Shell,
        /** The harness's own "run in the background" flag (claude Bash `run_in_background`). */
        val background: Boolean = false,
        val harness: String,
        /** The `agenthq` launcher the rewritten command calls; null = `agenthq` from PATH. */
        val launcher: String? = null,
        val windows: Boolean = System.getProperty("os.name").lowercase().contains("win"),
    )

    data class Result(val command: String, val detached: Boolean, val matched: String)

    /** Long by nature (builds, test runs, package installs): the tool profiles' unconditional `long` commands ([ToolProfiles]). */
    val LONG: Set<String> get() = ToolProfiles.active.filter { it.long && it.longIf.isEmpty() }.flatMap { it.commands }.toSet()

    /** The `agenthq` subcommands that already chose how they are watched. */
    private val CHOSEN = setOf("bg", "run-watched", "unwatched")

    /** Words that start a command without being its program. */
    private val PREFIX = setOf("nohup", "setsid", "time", "exec", "command", "builtin", "env", "call", "&", "nice", "stdbuf")

    fun rewrite(r: Request): Result? {
        val (cd, body) = splitLeadingCd(r.command.trim(), r.shell)
        if (body.isBlank()) return null
        val segments = segments(body)
        var matched: String? = null
        var bg = r.background
        for (seg in segments) {
            val words = words(seg.text)
            val prog = program(words) ?: continue
            val base = baseName(prog.first)
            if (base == "agenthq" || base == "infantry") {
                val sub = words.getOrNull(prog.second + 1)
                if (sub in CHOSEN) return null
                if (!(r.background || seg.background)) return null
            }
            if (seg.background) bg = true
            if (r.shell == Shell.POWERSHELL && words.any { it.equals("Start-Process", true) || it.equals("Start-Job", true) || it.equals("saps", true) }) bg = true
            if (words.take(prog.second).any { it == "nohup" || it == "setsid" }) bg = true
            if (matched == null && ToolProfiles.isLong(prog.first, words.drop(prog.second + 1))) matched = base
        }
        if (matched == null && !bg) return null
        val script = if (bg) stripBackground(body, r.shell) else body
        val label = label(script)
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(script.toByteArray(Charsets.UTF_8))
        val mode = if (bg) "--notify" else "--attached"
        val opts = "bg run $mode --hook ${r.harness} --name '$label'"
        val call = when (r.shell) {
            Shell.BASH -> {
                val l = r.launcher?.let { shQuote(toShPath(it, r.windows)) } ?: "agenthq"
                "$l $opts --shell \"\${BASH:-\${SHELL:-sh}}\" --script-b64 $b64"
            }
            Shell.POWERSHELL -> {
                val l = r.launcher?.let { "& '" + it.replace("'", "''") + "'" } ?: "agenthq"
                "$l $opts --shell (Get-Process -Id \$PID).Path --script-b64 $b64"
            }
        }
        return Result(cd + call, detached = bg, matched = matched ?: "background")
    }

    /** The script a `--script-b64` carries. */
    fun decode(b64: String): String = String(Base64.getUrlDecoder().decode(b64), Charsets.UTF_8)

    private data class Segment(val text: String, val background: Boolean)

    /** `cd x && rest` -> ("cd x && ", rest); PowerShell also `cd x; rest` / `Set-Location x; rest`. */
    private fun splitLeadingCd(cmd: String, shell: Shell): Pair<String, String> {
        val sep = if (shell == Shell.POWERSHELL) "(?:&&|;)" else "&&"
        val m = Regex("^((?:cd|Set-Location|pushd)\\s+(?:\"[^\"]*\"|'[^']*'|[^\\s;&|]+)\\s*$sep\\s*)(.*)$", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(cmd) ?: return "" to cmd
        return m.groupValues[1] to m.groupValues[2]
    }

    /** Split on `&&`, `||`, `;`, `|`, newlines and a lone `&` (outside quotes); a segment ended by a lone `&` is backgrounded. */
    private fun segments(cmd: String): List<Segment> {
        val out = ArrayList<Segment>()
        val cur = StringBuilder()
        var q: Char? = null
        var i = 0
        fun flush(bg: Boolean) { if (cur.isNotBlank()) out += Segment(cur.toString(), bg); cur.setLength(0) }
        while (i < cmd.length) {
            val c = cmd[i]
            if (q != null) {
                if (c == q) q = null
                cur.append(c); i++; continue
            }
            when {
                c == '\'' || c == '"' -> { q = c; cur.append(c) }
                c == '&' && cmd.getOrNull(i + 1) == '&' -> { flush(false); i++ }
                c == '|' && cmd.getOrNull(i + 1) == '|' -> { flush(false); i++ }
                c == '&' && (cmd.getOrNull(i + 1) == '>' || cmd.getOrNull(i - 1) == '>') -> cur.append(c) // &> and 2>&1
                c == '&' && cur.isBlank() -> cur.append(c) // PowerShell's call operator `& 'x\gradlew.bat'`
                c == '&' -> flush(true)
                c == ';' || c == '|' || c == '\n' -> flush(false)
                else -> cur.append(c)
            }
            i++
        }
        flush(false)
        return out
    }

    private fun words(seg: String): List<String> =
        Regex("\"[^\"]*\"|'[^']*'|\\S+").findAll(seg.trim()).map { it.value.trim('"', '\'') }.toList()

    /** The program word and its index: past `VAR=x` assignments and [PREFIX] words. */
    private fun program(words: List<String>): Pair<String, Int>? {
        for ((i, w) in words.withIndex()) {
            if (Regex("^[A-Za-z_][A-Za-z0-9_]*=.*").matches(w)) continue
            if (w.lowercase() in PREFIX) continue
            if (w.startsWith("-")) continue // a PREFIX word's own option (`nice -n 5`)
            return w to i
        }
        return null
    }

    private fun baseName(p: String): String = ToolProfiles.baseName(p)

    /** The command with its trailing `&` / `disown` removed: the runner itself is what goes to the background. */
    private fun stripBackground(cmd: String, shell: Shell): String {
        var s = cmd.trimEnd()
        s = s.replace(Regex("(?:;|&&)?\\s*disown(?:\\s+-[a-z]+)?\\s*$"), "").trimEnd()
        if (s.endsWith("&") && !s.endsWith("&&")) s = s.dropLast(1).trimEnd()
        return s
    }

    /** A label safe in single quotes on every shell and through the `.cmd` launcher (no quotes, `/`, `%`, `!`, `^`, `=`, `,`). */
    private fun label(script: String): String =
        script.lineSequence().first().replace(Regex("[^A-Za-z0-9 ._:-]"), " ").replace(Regex("\\s+"), " ").trim().take(60).ifBlank { "hooked command" }

    private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun toShPath(s: String, windows: Boolean): String {
        if (!windows || s.length < 3 || s[1] != ':' || (s[2] != '\\' && s[2] != '/')) return s
        return "/" + s[0].lowercaseChar() + s.substring(2).replace('\\', '/')
    }
}
