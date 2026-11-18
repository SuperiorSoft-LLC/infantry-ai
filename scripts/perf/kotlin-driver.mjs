// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RUST-PERF-1: drives the SAME scenario as app/shell/src/daemon/perf-scenario.ts against a Kotlin daemon, over its
// WebSocket (a plain client: no Origin, loopback, no token). Writes the same {event, t} JSON lines.
//   node kotlin-driver.mjs <ws url> <agents> <idle seconds> <model> <out file> <project folder>
// The daemon runs sessions only in a project, so the driver first saves one whose directory is the throwaway folder.
import { appendFileSync } from "node:fs";

const [url, nArg, idleArg, model, out, folder] = process.argv.slice(2);
const n = Number(nArg), idleSec = Number(idleArg);
const FIRST = "Reply with the word ok and nothing else.";
const STREAM = "Write about 300 words about rivers, in plain prose. Do not use any tools.";
const report = (event, extra = {}) => appendFileSync(out, JSON.stringify({ event, t: Date.now(), ...extra }) + "\n");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const sessions = new Map(); // id -> { finished, state, workingDir }
let ready = false;
let projectId = null;
const ws = new WebSocket(url);
const send = (o) => ws.send(JSON.stringify({ data: "", ...o }));
ws.onmessage = (m) => {
  let e;
  try { e = JSON.parse(m.data); } catch { return; }
  if (e.type === "ready") ready = true;
  if (e.type === "project" && !projectId) {
    try { const p = JSON.parse(e.data); if (p.directory && p.directory.toLowerCase() === folder.toLowerCase()) projectId = p.id; } catch {}
  }
  const sid = e.sessionId;
  if (e.type === "session" && sid) {
    let r = {};
    try { r = JSON.parse(e.data); } catch {}
    const s = sessions.get(sid) ?? { finished: 0 };
    s.state = r.state; s.workingDir = r.workingDir;
    sessions.set(sid, s);
  }
  if (e.type === "finished" && sid) {
    const s = sessions.get(sid) ?? { finished: 0 };
    s.finished += 1;
    sessions.set(sid, s);
  }
  if (e.type === "error") console.error(`[daemon error] ${String(e.data).slice(0, 200)}`);
};
ws.onerror = (e) => { console.error("socket error", e.message ?? e); };
ws.onclose = (e) => { console.error(`socket closed ${e.code} ${e.reason}`); };

async function until(ok, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (ok()) return true; await sleep(250); }
  return ok();
}

if (!(await until(() => ready, 30_000))) { console.error("no ready frame"); process.exit(2); }
report("ui-ready");
send({ type: "project_save", data: JSON.stringify({ name: "perf", directory: folder }) });
if (!(await until(() => projectId, 15_000))) { console.error("no project frame"); process.exit(3); }
report("spawn-start");
for (let k = 0; k < n; k++) {
  send({ type: "new", data: JSON.stringify({ provider: "claude", model, projectId }) });
  send({ type: "prompt", data: FIRST, requestId: `send:perf-${Date.now()}-${k}` });
  await sleep(300);
}
const up = await until(() => sessions.size >= n && [...sessions.values()].every((s) => s.finished >= 1), 240_000);
report("agents-up", { ok: up, sessions: sessions.size, dirs: [...new Set([...sessions.values()].map((s) => s.workingDir))] });
await sleep(2000);
report("idle-start");
await sleep(idleSec * 1000);
report("idle-end");
const before = new Map([...sessions].map(([id, s]) => [id, s.finished]));
report("stream-start");
let k = 0;
for (const id of before.keys()) send({ type: "prompt", data: STREAM, sessionId: id, requestId: `send:perf-s-${Date.now()}-${k++}` });
const done = await until(() => [...before].every(([id, f]) => sessions.get(id).finished > f), 240_000);
report("stream-end", { ok: done });
// The sessions are left running: the harness kills the app (and with it every session) by PID.
report("scenario-done", { ids: [...sessions.keys()] });
ws.close();
process.exit(0);
