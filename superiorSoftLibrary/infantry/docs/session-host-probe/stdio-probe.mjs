// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
// BUG-M2-11a harness probe: does a CLI keep ONE process across several turns written to an open stdin?
// usage: node stdio-probe.mjs <config.json>
// config: { name, argv: [exe, ...args], cwd, env?, rawOut?, bootIf?, exitTimeoutMs?, closeStdinAtEnd?,
//           steps: [{ label, write, also?: [{ delayMs, write }], until, timeoutMs? }] }
//   until / bootIf are JS expressions over `o` (the parsed JSON line, or null) and `line` (the raw string).
//   `also` lines are written delayMs after the step's own write: that is how a mid-turn interrupt / abort is sent.
//   argv, cwd and rawOut may use ${ENV_VAR}; INFANTRY-SESSION-HOST-PROBE.md lists the variables each config needs.
// Spawned with windowsHide; stdout is split on \n only; one summarised, timestamped line per record.
import { spawn } from 'node:child_process';
import { readFileSync, appendFileSync } from 'node:fs';

const cfg = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const sub = (s) => (typeof s === 'string' ? s.replace(/\$\{(\w+)\}/g, (_, k) => process.env[k] ?? '') : s);
cfg.argv = cfg.argv.map(sub);
cfg.cwd = sub(cfg.cwd);
cfg.rawOut = sub(cfg.rawOut);
const t0 = Date.now();
const ts = () => `+${String(Date.now() - t0).padStart(6)}ms`;
const log = (s) => console.log(`${ts()} ${s}`);
const pred = (src) => new Function('o', 'line', `try { return !!(${src}); } catch { return false; }`);

const child = spawn(cfg.argv[0], cfg.argv.slice(1), {
  cwd: cfg.cwd, env: { ...process.env, ...(cfg.env || {}) }, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true,
});
log(`[${cfg.name}] spawned pid=${child.pid} argv=${JSON.stringify(cfg.argv.slice(1))}`);
let exited = null;
child.on('exit', (code, sig) => { exited = { code, sig }; log(`EXIT code=${code} signal=${sig}`); });
child.on('error', (e) => log(`SPAWN ERROR ${e.message}`));

let buf = '';
let waiter = null;
let stepLines = 0, stepBoot = 0;
const bootIf = cfg.bootIf ? pred(cfg.bootIf) : () => false;
const summarise = (o, line) => {
  if (!o) return `text: ${line.slice(0, 140)}`;
  const kind = [o.type, o.subtype, o.command, o.assistantMessageEvent?.type].filter(Boolean).join('/');
  const sid = o.session_id || o.sessionId || o.sessionID || o.data?.sessionId || '';
  let text = '';
  if (o.type === 'result') text = `result=${JSON.stringify(o.result)} is_error=${o.is_error} num_turns=${o.num_turns}`;
  else if (o.type === 'assistant' && o.message?.content) text = 'content=' + JSON.stringify(o.message.content.map(c => c.text ?? c.type)).slice(0, 120);
  else if (o.type === 'response') text = `success=${o.success} data=${JSON.stringify(o.data ?? o.error ?? null).slice(0, 220)}`;
  else if (o.assistantMessageEvent?.type === 'text_delta') text = `delta=${JSON.stringify(o.assistantMessageEvent.delta)}`;
  else if (o.type === 'agent_end' || o.type === 'agent_settled') text = JSON.stringify(o).slice(0, 160);
  else if (o.type === 'system' && o.subtype === 'init') text = `model=${o.model} cwd=${o.cwd} tools=${(o.tools || []).length}`;
  return `${kind}${sid ? ` sid=${sid}` : ''}${text ? ' ' + text : ''}`;
};
const onLine = (line) => {
  if (cfg.rawOut) appendFileSync(cfg.rawOut, line + '\n');
  let o = null;
  try { o = JSON.parse(line); } catch { /* not JSON */ }
  stepLines++;
  if (bootIf(o, line)) stepBoot++;
  const quiet = o && o.type === 'message_update' && o.assistantMessageEvent?.type !== 'text_delta';
  if (!quiet) log(`OUT ${summarise(o, line)}`);
  if (waiter && waiter.until(o, line)) { const w = waiter; waiter = null; w.resolve(true); }
};
child.stdout.on('data', (d) => {
  buf += d.toString('utf8');
  let i;
  while ((i = buf.indexOf('\n')) >= 0) { const l = buf.slice(0, i).replace(/\r$/, ''); buf = buf.slice(i + 1); if (l.trim()) onLine(l); }
});
child.stderr.on('data', (d) => d.toString('utf8').split('\n').filter(Boolean).forEach((l) => log(`ERR ${l.slice(0, 200)}`)));

const waitFor = (until, ms) => new Promise((resolve) => {
  waiter = { until, resolve };
  setTimeout(() => { if (waiter) { waiter = null; resolve(false); } }, ms);
});

for (const step of cfg.steps) {
  stepLines = 0; stepBoot = 0;
  log(`--- STEP ${step.label}: write ${step.write.slice(0, 120)}`);
  child.stdin.write(step.write + '\n');
  for (const a of step.also || []) setTimeout(() => { log(`--- mid-turn write: ${a.write.slice(0, 120)}`); child.stdin.write(a.write + '\n'); }, a.delayMs);
  const ok = await waitFor(pred(step.until), step.timeoutMs || 120000);
  log(`--- STEP ${step.label} ${ok ? 'END-OF-TURN SEEN' : 'TIMED OUT'}; lines=${stepLines} bootLines=${stepBoot}; ` +
      `process alive=${exited === null} pid=${child.pid}`);
  if (!ok || exited) break;
}
if (cfg.closeStdinAtEnd !== false && exited === null) {
  log('--- closing stdin');
  child.stdin.end();
  const deadline = Date.now() + (cfg.exitTimeoutMs || 15000);
  while (exited === null && Date.now() < deadline) await new Promise((r) => setTimeout(r, 100));
  if (exited === null) { log('did NOT exit after stdin close; killing'); child.kill(); }
}
await new Promise((r) => setTimeout(r, 300));
process.exit(0);
