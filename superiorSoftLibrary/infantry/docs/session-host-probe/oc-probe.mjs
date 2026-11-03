// BUG-M2-11a probe: opencode serve, several turns + an abort on ONE server process, then stdin-close behaviour.
// usage: OPENCODE_EXE=<path to opencode.exe> node oc-probe.mjs <cwd> <modelID>
//   OPENCODE_EXE must be the real binary (on Windows the npm `opencode` shim is a .cmd, which spawn cannot run).
//   modelID is an `opencode` provider model, e.g. deepseek-v4.1-flash-free (`opencode models` lists them).
// The server password is a fresh random UUID per run and is never printed. Windows-only teardown (taskkill /T /F).
import { spawn, execSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';

if (process.argv.length < 4) { console.error('usage: node oc-probe.mjs <cwd> <modelID>'); process.exit(2); }
const exe = process.env.OPENCODE_EXE || 'opencode';
const cwd = process.argv[2];
const port = 40000 + Math.floor(Math.random() * 20000);
const pw = randomUUID();
const t0 = Date.now();
const log = (s) => console.log(`+${String(Date.now() - t0).padStart(6)}ms ${s}`);
const auth = 'Basic ' + Buffer.from(`opencode:${pw}`).toString('base64');

const child = spawn(exe, ['serve', '--port', String(port), '--hostname', '127.0.0.1'], {
  cwd, env: { ...process.env, OPENCODE_SERVER_PASSWORD: pw }, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
});
let exited = null;
child.on('exit', (c, s) => { exited = { c, s }; log(`EXIT code=${c} signal=${s}`); });
log(`spawned pid=${child.pid} argv=["serve","--port","${port}","--hostname","127.0.0.1"]`);
let url = null;
const onOut = (tag) => (d) => d.toString().split('\n').filter(Boolean).forEach((l) => {
  log(`${tag} ${l.slice(0, 160)}`);
  const m = l.match(/listening on (https?:\/\/\S+)/); if (m) url = m[1];
});
child.stdout.on('data', onOut('OUT')); child.stderr.on('data', onOut('ERR'));
while (!url && !exited && Date.now() - t0 < 30000) await new Promise((r) => setTimeout(r, 50));
if (!url) { log('no "listening on" line within 30s'); process.exit(1); }
log(`BOOTED url=${url}`);

const post = async (path, body) => {
  const r = await fetch(url + path, { method: 'POST', headers: { Authorization: auth, 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
  const t = await r.text(); if (!r.ok) throw new Error(`${path} -> ${r.status} ${t.slice(0, 200)}`); return t ? JSON.parse(t) : null;
};

let session = null, waiter = null, sawBusy = false;
const texts = new Map(); const asst = new Set();
(async () => {
  const r = await fetch(url + '/event', { headers: { Authorization: auth, Accept: 'text/event-stream' } });
  const dec = new TextDecoder(); let buf = '';
  for await (const chunk of r.body) {
    buf += dec.decode(chunk, { stream: true }); let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim(); buf = buf.slice(i + 1);
      if (!line.startsWith('data:')) continue;
      let ev; try { ev = JSON.parse(line.slice(5)); } catch { continue; }
      const p = ev.properties || {}; const sid = p.sessionID || p.part?.sessionID || p.info?.sessionID;
      if (sid !== session) continue;
      // Only assistant text counts as the reply: the user's own prompt arrives as a text part too.
      if (ev.type === 'message.updated' && p.info?.role === 'assistant') asst.add(p.info.id);
      if (ev.type === 'message.part.updated' && p.part?.type === 'text' && asst.has(p.part.messageID)) texts.set(p.part.id, p.part.text);
      const st = ev.type === 'session.status' ? p.status?.type : ev.type === 'session.idle' ? 'idle' : null;
      if (ev.type === 'session.error') log(`EVENT session.error ${JSON.stringify(p.error || p).slice(0, 160)}`);
      if (st === 'busy') sawBusy = true;
      if (st === 'idle' && sawBusy && waiter) { const w = waiter; waiter = null; w(); }
    }
  }
})().catch((e) => log(`SSE ended: ${e.message}`));
await new Promise((r) => setTimeout(r, 300));

session = (await post('/session', {})).id;
log(`session ${session}`);
const turn = async (label, text, abortAfterMs) => {
  texts.clear(); sawBusy = false;
  const done = new Promise((r) => { waiter = r; });
  const t1 = Date.now();
  await post(`/session/${session}/prompt_async`, { parts: [{ type: 'text', text }], model: { providerID: 'opencode', modelID: process.argv[3] } });
  if (abortAfterMs) setTimeout(() => { log(`--- POST /session/${session}/abort`); post(`/session/${session}/abort`, {}).then((x) => log(`abort answered ${JSON.stringify(x)}`)).catch((e) => log(`abort failed ${e.message}`)); }, abortAfterMs);
  const ok = await Promise.race([done.then(() => true), new Promise((r) => setTimeout(() => r(false), 150000))]);
  const reply = [...texts.values()].join('').trim();
  log(`--- TURN ${label} ${ok ? 'IDLE SEEN' : 'TIMED OUT'} after ${Date.now() - t1}ms; reply=${JSON.stringify(reply.slice(0, 80))}${reply.length > 80 ? `…(${reply.length} chars)` : ''}; server pid=${child.pid} alive=${exited === null}`);
};
await turn('turn1', 'Reply with exactly: ONE');
await turn('turn2', 'Reply with exactly: TWO');
await turn('turn3-abort', 'Write a 1500-word essay on the history of bridges. Do not use any tools.', 2500);
await turn('turn4-after-abort', 'Reply with exactly: FOUR');

log('--- closing stdin, waiting 4s');
child.stdin.end();
await new Promise((r) => setTimeout(r, 4000));
log(`after stdin close: alive=${exited === null}`);
if (exited === null) { execSync(`taskkill /PID ${child.pid} /T /F`); log('tree-killed with taskkill /T /F'); }
await new Promise((r) => setTimeout(r, 500));
process.exit(0);
