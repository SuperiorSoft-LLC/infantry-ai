// Infantry AI STALL-2 watch hook for pi (generated; the daemon rewrites it at start). docs/STALL.md.
// pi's `tool_call` event lets an extension mutate `event.input` in place before the tool runs. Before a bash/powershell
// call, the daemon's ONE rule set (`POST <AGENTHQ_MAIL_BASE>/watch/rewrite/pi`) may rewrite the command to run through
// the watched runner (`agenthq bg run --attached|--notify --hook pi ...`). Any failure leaves the command as it was:
// this handler never throws (a throwing tool_call handler would block the tool).
export default function (pi: any) {
  pi.on("tool_call", async (event: any) => {
    try {
      const base = process.env.AGENTHQ_MAIL_BASE
      if (process.env.AGENTHQ_WATCH !== "on" || !base) return
      const tool = String(event?.toolName || "").toLowerCase()
      if (tool !== "bash" && tool !== "powershell") return
      const command = event?.input?.command
      if (typeof command !== "string" || !command.trim()) return
      const res = await fetch(base.replace(/\/+$/, "") + "/watch/rewrite/pi", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "Authorization": "Bearer " + (process.env.AGENTHQ_TOKEN || ""),
          "X-Agenthq-Session": process.env.AGENTHQ_SESSION_ID || "",
        },
        body: JSON.stringify({ tool, command, background: false }),
        signal: AbortSignal.timeout(5000),
      })
      if (!res.ok) return
      const out: any = await res.json()
      if (out && typeof out.command === "string" && out.command) event.input.command = out.command
    } catch (e) {
      // leave the command unchanged
    }
  })
}
