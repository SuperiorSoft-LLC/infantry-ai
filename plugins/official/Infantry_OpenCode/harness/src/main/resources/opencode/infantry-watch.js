// Infantry AI STALL-2 watch hook for opencode (generated; the daemon rewrites it at start). docs/STALL.md.
// Before a bash/powershell tool call runs, the daemon's ONE rule set (`POST <INFANTRY_MAIL_BASE>/watch/rewrite/opencode`)
// may rewrite it to run through the watched runner (`infantry bg run --attached|--notify --hook opencode ...`). Any
// failure (daemon down, timeout, bad answer) leaves the command as it was: this hook never blocks a call.
export const InfantryWatch = async () => {
  const base = process.env.INFANTRY_MAIL_BASE
  const on = process.env.INFANTRY_WATCH === "on"
  return {
    "tool.execute.before": async (input, output) => {
      if (!on || !base) return
      const tool = String((input && input.tool) || "").toLowerCase()
      if (tool !== "bash" && tool !== "powershell") return
      const command = output && output.args && output.args.command
      if (typeof command !== "string" || !command.trim()) return
      try {
        const res = await fetch(base.replace(/\/+$/, "") + "/watch/rewrite/opencode", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Authorization": "Bearer " + (process.env.INFANTRY_TOKEN || ""),
            "X-Infantry-Session": process.env.INFANTRY_SESSION_ID || "",
          },
          body: JSON.stringify({ tool, command, background: false }),
          signal: AbortSignal.timeout(5000),
        })
        if (!res.ok) return
        const out = await res.json()
        if (out && typeof out.command === "string" && out.command) output.args.command = out.command
      } catch (e) {
        // leave the command unchanged
      }
    },
  }
}
