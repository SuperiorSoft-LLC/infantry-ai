// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RUST-PERF-1: end-to-end check of agents in the Tauri app, driven in-page over the WebView2 DevTools protocol (DOM
// events inside the page; no desktop input). ui-smoke.ps1 starts the app with a debugging port and a throwaway folder.
//   node ui-smoke.mjs <devtools port> <throwaway project folder>
const [port, folder] = process.argv.slice(2);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let targets = [];
for (let i = 0; i < 60 && !targets.some((t) => t.type === "page"); i++) {
  try { targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json(); } catch {}
  await sleep(500);
}
const page = targets.find((t) => t.type === "page");
if (!page) { console.log("FAIL no page target"); process.exit(1); }
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => (ws.onopen = r));
let id = 0;
const waiting = new Map();
ws.onmessage = (m) => {
  const msg = JSON.parse(m.data);
  if (msg.id && waiting.has(msg.id)) { waiting.get(msg.id)(msg); waiting.delete(msg.id); }
};
async function js(expr) {
  const n = ++id;
  ws.send(JSON.stringify({ id: n, method: "Runtime.evaluate", params: { expression: expr, awaitPromise: true, returnByValue: true } }));
  const r = await new Promise((res) => waiting.set(n, res));
  if (r.result?.exceptionDetails) throw new Error(r.result.exceptionDetails.exception?.description ?? "eval failed");
  return r.result?.result?.value;
}

// In-page helpers: React-compatible value setting, clicks by label/text, Enter in the composer.
await js(`
  window.__t = {
    btn(label) {
      return [...document.querySelectorAll("button")].find((b) => b.getAttribute("aria-label") === label || b.textContent.trim() === label);
    },
    click(label) { const b = this.btn(label); if (!b) throw new Error("no button " + label); b.click(); },
    set(el, v) {
      const proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, "value").set.call(el, v);
      el.dispatchEvent(new Event("input", { bubbles: true }));
    },
    prompt(text) {
      const ta = document.querySelector('textarea[aria-label="Prompt"]');
      this.set(ta, text);
      ta.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", bubbles: true }));
    },
    main() { return document.querySelector("main")?.innerText ?? ""; },
    count(s) { return this.main().split(s).length - 1; },
  };
  true;
`);

async function until(expr, ms, what) {
  const end = Date.now() + ms;
  while (Date.now() < end) { if (await js(expr)) return true; await sleep(500); }
  console.log(`  (timed out waiting for ${what})`);
  return false;
}
let all = true;
const check = (name, ok, detail = "") => { all &&= ok; console.log(`${ok ? "PASS" : "FAIL"} ${name} ${detail}`); };

// 1. New agent dialog -> a session in the throwaway folder, card in the sidebar.
await js(`window.__TAURI_INTERNALS__.invoke("local_set_project", { folder: ${JSON.stringify(folder)} }).then(() => true)`);
await js(`__t.click("New agent"); true`);
await sleep(300);
await js(`__t.set(document.getElementById("agent-name"), "Smoke"); __t.click("Create agent"); true`);
check("new agent card", await until(`document.querySelector('aside[aria-label="Agents"]').innerText.includes("Smoke")`, 15000, "card"));

// 2. A turn from the composer streams into the transcript.
await sleep(1500);
await js(`__t.prompt("Reply with the word ok and nothing else."); true`);
check("turn completes", await until(`__t.count("finished: succeeded") >= 1`, 180000, "first finish"));
const text = await js(`__t.main()`);
check("reply in transcript", /\bok\b/i.test(text.split("Reply with the word ok")[1] ?? ""));

// 3. Interrupt a streaming turn with Stop.
await js(`__t.prompt("Write 800 words about mountains, in plain prose. Do not use any tools."); true`);
await until(`!!__t.btn("Stop") && !__t.btn("Stop").disabled`, 20000, "busy");
await sleep(5000);
await js(`__t.click("Stop"); true`);
check("interrupt", await until(`__t.count("finished: interrupted") >= 1`, 60000, "interrupted"));

// 4. A second agent runs alongside.
await js(`__t.click("New agent"); true`);
await sleep(300);
await js(`__t.set(document.getElementById("agent-name"), "Second"); __t.click("Create agent"); true`);
check("two agents in the sidebar", await until(`(() => { const t = document.querySelector('aside[aria-label="Agents"]').innerText; return t.includes("Smoke") && t.includes("Second"); })()`, 15000, "two cards"));

// 5. Resume: exit the first session (/exit), then resume it from the Resume dialog and ask what it said first.
const list0 = await js(`window.__TAURI_INTERNALS__.invoke("local_resumable", { folder: ${JSON.stringify(folder)} })`);
const first = list0.find((r) => r.title.startsWith("Reply with the word ok"));
check("resume list shows the session", !!first, first ? first.id : JSON.stringify(list0));
if (first) {
  // Select the first agent's card (the sidebar orders newest first), then /exit it.
  await js(`[...document.querySelectorAll('aside[aria-label="Agents"] [role], aside[aria-label="Agents"] button')].find((e) => e.innerText?.includes("Smoke"))?.click(); true`);
  await sleep(800);
  await js(`__t.prompt("/exit"); true`);
  const exited = await until(`window.__TAURI_INTERNALS__.invoke("local_resumable", { folder: ${JSON.stringify(folder)} }).then((l) => !l.find((r) => r.id === ${JSON.stringify(first.id)}).running)`, 60000, "exit");
  check("session exited", exited);
  await js(`__t.click("Resume"); true`);
  await until(`!!document.querySelector('[role="option"]')`, 10000, "resume list");
  await js(`[...document.querySelectorAll('[role="option"]')].find((o) => o.innerText.includes(${JSON.stringify(first.id)})).click(); true`);
  await sleep(300);
  await js(`[...document.querySelectorAll('[role="dialog"] button')].find((b) => b.textContent.trim() === "Resume").click(); true`);
  await sleep(4000);
  const before = await js(`__t.count("finished: succeeded")`);
  await js(`__t.prompt("What exact word did you reply with in your very first answer of this conversation? Reply with only that word."); true`);
  const ok = await until(`__t.count("finished: succeeded") > ${before}`, 180000, "resumed turn");
  const t2 = await js(`__t.main()`);
  const tail = t2.split("What exact word did you reply with")[1] ?? "";
  check("resume (--resume) remembers", ok && /\bok\b/i.test(tail), tail.slice(0, 120).replace(/\s+/g, " "));
}
console.log(all ? "UI SMOKE OK" : "UI SMOKE FAILED");
ws.close();
process.exit(all ? 0 : 1);
