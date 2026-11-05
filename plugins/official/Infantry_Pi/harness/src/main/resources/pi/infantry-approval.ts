// Infantry AI tool-approval gate for pi (HARNESS-GAP G5). pi has no approval prompt of its own, so this extension enforces the session's
// ToolApproval mode (env INFANTRY_TOOL_APPROVAL: DENY | ALLOW_EDITS | ALLOW_ALL | ASK) in its `tool_call` handler.
//   ALLOW_ALL: every tool runs.   ALLOW_EDITS: read-only tools and file edits run, everything else (bash, unknown) is blocked.
//   DENY: only read-only tools run.   ASK: read-only tools run, the rest asks the owner through ctx.ui.confirm (the harness relays the
//   rpc `extension_ui_request` to the owner; with no UI, as in --mode json, confirm answers false, so the call is blocked).
// An unset or unknown mode is treated as DENY: fail closed.
const READ_ONLY = new Set(["read", "grep", "find", "ls", "todowrite"])
const EDITS = new Set(["edit", "write"])
export const MARKER = "infantry-approval:"

function summary(event: any): string {
  const input = event?.input || {}
  const text = String(input.command ?? input.path ?? input.file_path ?? JSON.stringify(input) ?? "")
  return text.length > 300 ? text.slice(0, 300) + "..." : text
}

export default function (pi: any) {
  pi.on("tool_call", async (event: any, ctx: any) => {
    const mode = String(process.env.INFANTRY_TOOL_APPROVAL || "DENY").toUpperCase()
    if (mode === "ALLOW_ALL") return
    const tool = String(event?.toolName || "").toLowerCase()
    if (READ_ONLY.has(tool) || tool.startsWith("infantry_") || tool.startsWith("mcp__infantry")) return
    if (mode === "ALLOW_EDITS" && EDITS.has(tool)) return
    if (mode === "ASK") {
      let ok = false
      try { ok = !!(await ctx?.ui?.confirm(MARKER + tool, summary(event))) } catch (e) { ok = false }
      if (ok) return
      return { block: true, reason: `${event?.toolName} was not approved by the owner` }
    }
    return { block: true, reason: `${event?.toolName} is not allowed: tool approval is ${mode}` }
  })
}
