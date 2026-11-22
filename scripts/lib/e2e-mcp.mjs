// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RR-12: call one tool of `infantry mcp serve` (stdio JSON-RPC) and print its text result.
//   node e2e-mcp.mjs <infantry-cli> <ws-url> <token-file> <tool> [<json args>]
// Exit 0 with the tool's text on stdout; exit 1 on a tool error (its text goes to stderr); `--tools` as the tool lists tool names.
// The state dir and the token come from the environment/args of the caller; nothing is printed beyond the tool's own answer.
import { spawn } from "node:child_process";
const [cli, url, tokenFile, tool, argText] = process.argv.slice(2);
const args = argText ? JSON.parse(argText) : {};
const child = spawn(cli, ["--url", url, "--token-file", tokenFile, "mcp", "serve", "--allow-kill-any"], { stdio: ["pipe", "pipe", "inherit"] });
let buf = "";
const waiting = new Map();
child.stdout.on("data", (d) => {
  buf += d;
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, i).trim();
    buf = buf.slice(i + 1);
    if (!line) continue;
    let m;
    try { m = JSON.parse(line); } catch { continue; }
    const w = waiting.get(m.id);
    if (w) { waiting.delete(m.id); w(m); }
  }
});
let n = 0;
const rpc = (method, params) => new Promise((resolve) => {
  const id = ++n;
  waiting.set(id, resolve);
  child.stdin.write(JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n");
});
const timer = setTimeout(() => { console.error("e2e-mcp: timeout"); child.kill(); process.exit(1); }, 60000);
await rpc("initialize", { protocolVersion: "2024-11-05", capabilities: {}, clientInfo: { name: "runner-e2e", version: "1" } });
child.stdin.write(JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" }) + "\n");
let code = 0;
if (tool === "--tools") {
  const r = await rpc("tools/list", {});
  console.log((r.result?.tools ?? []).map((t) => t.name).join("\n"));
} else {
  const r = await rpc("tools/call", { name: tool, arguments: args });
  const text = (r.result?.content ?? []).map((c) => c.text ?? "").join("\n");
  if (r.error || r.result?.isError) { console.error(r.error?.message ?? text); code = 1; } else console.log(text);
}
clearTimeout(timer);
child.kill();
process.exit(code);
