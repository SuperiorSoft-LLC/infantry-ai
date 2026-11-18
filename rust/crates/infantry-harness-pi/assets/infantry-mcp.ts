// SPDX-License-Identifier: Apache-2.0
// Infantry AI MCP bridge for pi (HARNESS-GAP G6, generalised to a list by MCP-PI-1). pi loads the user's own MCP servers itself; this
// extension starts the servers the session's MCP plan delivers (env INFANTRY_PI_MCP = {"servers":[{"name","prefix","command":[...],"env":{...}}]},
// from HarnessHost.mcpServers: infantry plus the granted Infantry-owned and plugin servers) as child processes, speaks MCP over their stdio
// (newline-delimited JSON-RPC) and registers every tool as a pi tool with the same schema and description. A server with "prefix" gets the name pi
// gives its own servers' tools (mcp__<server>__<tool>); the infantry server keeps its plain tool names. A legacy single {"command","env"} still works.
// Without INFANTRY_PI_MCP, or when a server does not answer, that server registers nothing and pi runs without it.
import { spawn } from "node:child_process"

const TIMEOUT_MS = Number(process.env.INFANTRY_PI_MCP_TIMEOUT_MS || 30000)
const safe = (s: string) => s.replace(/[^A-Za-z0-9_]/g, "_")

async function bridge(pi: any, spec: any) {
  if (!spec || !Array.isArray(spec.command) || spec.command.length === 0) return
  let child: any
  try {
    child = spawn(spec.command[0], spec.command.slice(1), { env: { ...process.env, ...(spec.env || {}) }, stdio: ["pipe", "pipe", "ignore"], windowsHide: true })
  } catch (e) { return }
  child.on("error", () => {})
  const pending = new Map<number, { resolve: (v: any) => void; timer: any }>()
  let nextId = 1
  let buf = ""
  child.stdout.on("data", (chunk: any) => {
    buf += chunk.toString("utf8")
    let nl: number
    while ((nl = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, nl).trim()
      buf = buf.slice(nl + 1)
      if (!line) continue
      try {
        const msg = JSON.parse(line)
        const p = msg && msg.id != null ? pending.get(msg.id) : undefined
        if (p) { clearTimeout(p.timer); pending.delete(msg.id); p.resolve(msg) }
      } catch (e) { /* not a JSON-RPC line */ }
    }
  })
  child.on("exit", () => { for (const [id, p] of pending) { clearTimeout(p.timer); p.resolve({ error: { message: "infantry MCP server exited" } }) } pending.clear() })
  const send = (obj: any) => { try { child.stdin.write(JSON.stringify(obj) + "\n") } catch (e) { /* closed */ } }
  const request = (method: string, params: any): Promise<any> => new Promise((resolve) => {
    const id = nextId++
    const timer = setTimeout(() => { pending.delete(id); resolve({ error: { message: `${method} timed out` } }) }, TIMEOUT_MS)
    pending.set(id, { resolve, timer })
    send({ jsonrpc: "2.0", id, method, params })
  })

  const init = await request("initialize", { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "infantry-pi", version: "1.0.0" } })
  if (init.error) { try { child.kill() } catch (e) {} return }
  send({ jsonrpc: "2.0", method: "notifications/initialized" })
  const listed = await request("tools/list", {})
  const tools: any[] = listed?.result?.tools || []
  if (listed.error || tools.length === 0) { try { child.kill() } catch (e) {} return }

  const piName = (n: string) => spec.prefix && spec.name ? `mcp__${safe(spec.name)}__${safe(n)}` : n
  for (const tool of tools) {
    pi.registerTool({
      name: piName(tool.name),
      label: piName(tool.name),
      description: tool.description || tool.name,
      parameters: tool.inputSchema || { type: "object", properties: {} },
      async execute(_toolCallId: string, params: any) {
        const res = await request("tools/call", { name: tool.name, arguments: params || {} })
        if (res.error) return { content: [{ type: "text", text: `Error: ${res.error.message || "MCP call failed"}` }], details: {}, isError: true }
        const content = Array.isArray(res.result?.content) ? res.result.content : [{ type: "text", text: JSON.stringify(res.result ?? {}) }]
        return { content, details: {}, isError: !!res.result?.isError }
      },
    })
  }
  pi.on("session_shutdown", () => { try { child.kill() } catch (e) {} })
}

export default async function (pi: any) {
  let parsed: any
  try { parsed = JSON.parse(process.env.INFANTRY_PI_MCP || "") } catch (e) { return }
  const specs: any[] = Array.isArray(parsed?.servers) ? parsed.servers : [parsed]
  await Promise.all(specs.map((spec) => bridge(pi, spec).catch(() => {})))
}
