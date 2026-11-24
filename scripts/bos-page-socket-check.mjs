// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Usage: node scripts/bos-page-socket-check.mjs [path-to-infantry-daemon.exe]
// BOS-10c check 3: the page's socket against a scrubbed, OS-style daemon: Origin = the OS app origin, no token.
import { spawn } from "node:child_process";
import net from "node:net";
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const exe = process.argv[2] ?? process.env.BOS_DAEMON_EXE ?? "infantry-daemon";
const free = () => new Promise((r) => { const s = net.createServer(); s.listen(0, "127.0.0.1", () => { const p = s.address().port; s.close(() => r(p)); }); });
const port = await free();
const dir = fs.mkdtempSync(path.join(os.tmpdir(), "bos10c-"));
const origin = "http://127.0.0.1:54321"; // the OS's per-window origin (any loopback port)
const env = {};
for (const [k, v] of Object.entries(process.env)) if (!/^(INFANTRY_|BATTALION_|AGENTHQ_)/i.test(k)) env[k] = v; // scrub
Object.assign(env, { // what the OS gives an app (contract v1), nothing else
  BATTALION_CONTRACT: "1", BATTALION_READY_FILE: path.join(dir, "ready.json"), BATTALION_TOKEN_FILE: path.join(dir, "token"),
  BATTALION_STATE_DIR: path.join(dir, "state"), BATTALION_APP_DIR: dir, BATTALION_UI_ORIGIN: origin,
});
fs.writeFileSync(env.BATTALION_TOKEN_FILE, "t".repeat(40));
const child = spawn(exe, ["--port", String(port), "--state-dir", path.join(dir, "state")], { env, stdio: "ignore", windowsHide: true });
const upgrade = (hdrs) => new Promise((res) => {
  const req = http.request({ host: "127.0.0.1", port, path: "/ws", headers: { Connection: "Upgrade", Upgrade: "websocket", "Sec-WebSocket-Version": "13", "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ==", ...hdrs } });
  req.on("upgrade", (r, s) => { s.destroy(); res(`101 (${r.statusCode})`); });
  req.on("response", (r) => { r.resume(); res(`refused ${r.statusCode}`); });
  req.on("error", (e) => res("error " + e.message));
  req.end();
});
let out = {};
try {
  for (let i = 0; i < 100; i++) { try { fs.accessSync(env.BATTALION_READY_FILE); break; } catch { await new Promise((r) => setTimeout(r, 200)); } }
  out.pid = child.pid; out.port = port;
  out.withOsOrigin_noToken = await upgrade({ Origin: origin });
  out.otherOrigin = await upgrade({ Origin: "http://127.0.0.1:1" });
  out.webOrigin = await upgrade({ Origin: "https://evil.example" });
  out.noOrigin_loopback = await upgrade({});
  out.tokenRequired = JSON.parse(await new Promise((r) => http.get({ host: "127.0.0.1", port, path: "/health" }, (x) => { let b = ""; x.on("data", (c) => (b += c)); x.on("end", () => r(b)); }))).tokenRequired ?? "(no field)";
} finally {
  const pid = child.pid; process.kill(pid); // by pid
  await new Promise((r) => setTimeout(r, 500));
  console.log(JSON.stringify(out, null, 1));
  fs.rmSync(dir, { recursive: true, force: true });
}
