// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Sandbox check (SANDBOX-1, ticket d4c1dbd9; copy it in first: docker compose -f docker/docker-compose.yml cp docker/sandbox-check.mjs infantry-ai:/tmp/): drive agents inside the running container with the OWNER token, to prove what
// a sandboxed agent can and cannot do. Node only, no deps. Run inside the container:
//   docker compose -f docker/docker-compose.yml exec infantry-ai node /tmp/sandbox-check.mjs setup
//   ... node /tmp/sandbox-check.mjs say <role> "<prompt>"            one turn, prints the reply
//   ... node /tmp/sandbox-check.mjs both <roleA> "<pA>" <roleB> "<pB>"  two turns started at the same moment
import { readFileSync, mkdirSync } from "node:fs";

const port = process.env.INFANTRY_PORT || "8787";
const token = readFileSync(process.env.INFANTRY_TOKEN_FILE || "/data/access-token", "utf8").trim();
const model = process.env.CHECK_MODEL || "opencode/deepseek-v4.1-flash-free";
const ws = new WebSocket(`ws://127.0.0.1:${port}/ws`, { headers: { Authorization: `Bearer ${token}` } });
const listeners = new Set();
let sessions = null;
ws.addEventListener("message", (ev) => {
  const e = JSON.parse(ev.data);
  if (e.type === "sessions") sessions = JSON.parse(e.data);
  for (const l of listeners) l(e);
});
const send = (type, data, extra = {}) => ws.send(JSON.stringify({ type, data: typeof data === "string" ? data : JSON.stringify(data), ...extra }));
function request(type, data) {
  const requestId = `chk-${Math.random().toString(36).slice(2)}`;
  return new Promise((resolve, reject) => {
    const l = (e) => { if (e.requestId === requestId) { listeners.delete(l); e.type === "error" ? reject(new Error(e.data)) : resolve(e); } };
    listeners.add(l); send(type, data, { requestId });
    setTimeout(() => { listeners.delete(l); reject(new Error(`${type}: no answer`)); }, 30000);
  });
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function freshSessions() { sessions = null; ws.close(); }

function turn(role, prompt, timeoutMs = 300000) {
  const s = sessions.find((x) => x.role === role && x.state !== "EXITED");
  if (!s) throw new Error(`no session for role ${role}`);
  let text = "", started = false;
  const t0 = Date.now();
  return new Promise((resolve) => {
    const l = (e) => {
      if (e.sessionId !== s.id) return;
      if (e.type === "error") { listeners.delete(l); resolve({ role, error: e.data }); }
      if (e.type === "turn") started = true;
      if (e.type === "text_delta" || e.type === "stdout") { try { const d = JSON.parse(e.data); text += d.text ?? d.delta ?? ""; } catch { text += e.data; } }
      if (e.type === "session" && started) {
        const st = JSON.parse(e.data);
        if (st.state === "IDLE" || st.state === "FAILED") { listeners.delete(l); resolve({ role, state: st.state, error: st.lastError, text: text.trim(), startedAt: t0, endedAt: Date.now() }); }
      }
    };
    listeners.add(l);
    send("prompt", prompt, { sessionId: s.id });
    setTimeout(() => { listeners.delete(l); resolve({ role, error: "timeout", text }); }, timeoutMs);
  });
}

await new Promise((res, rej) => { ws.addEventListener("open", res); ws.addEventListener("error", () => rej(new Error("cannot connect"))); });
while (!sessions) await sleep(100);
const [cmd, ...args] = process.argv.slice(2);
if (cmd === "setup") {
  mkdirSync("/work/sbx", { recursive: true });
  const project = JSON.parse((await request("project_save", { name: "Sandbox room", goal: "sandbox check", directory: "/work/sbx" })).data);
  for (const a of [
    { name: "Alpha", role: "pm", provider: "opencode", model },
    { name: "Beta", role: "backend", provider: "opencode", model },
    { name: "Gamma", role: "ideation", provider: "pi", model: process.env.CHECK_PI_MODEL || "openrouter/deepseek/deepseek-chat-v3.1:free" },
  ]) {
    const saved = JSON.parse((await request("agent_save", { ...a, projectId: project.id, instructions: "Keep answers short. Report tool results verbatim." })).data);
    send("new", { agentId: saved.id, projectId: project.id, toolApproval: "allowAll" });
    await sleep(1500);
    console.log(`agent ${saved.name} ${saved.role} ${saved.provider} ${saved.model}`);
  }
  console.log(`project ${project.id} ${project.directory}`);
} else if (cmd === "say") {
  const r = await turn(args[0], args[1]);
  console.log(JSON.stringify({ role: r.role, state: r.state, error: r.error }));
  console.log(r.text);
} else if (cmd === "both") {
  const rs = await Promise.all([turn(args[0], args[1]), turn(args[2], args[3])]);
  for (const r of rs) console.log(`${r.role}: ${r.state ?? ""} ${r.error ?? ""} start=+0 end=+${((r.endedAt - Math.min(...rs.map((x) => x.startedAt))) / 1000).toFixed(1)}s\n${r.text}`);
  const overlap = Math.min(...rs.map((x) => x.endedAt)) > Math.max(...rs.map((x) => x.startedAt));
  console.log(`overlapping turns: ${overlap}`);
}
ws.close();
process.exit(0);
