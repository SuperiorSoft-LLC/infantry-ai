#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PROMPT-4: live check that `opencode serve` under Tool approval `ask` forwards each permission to the prompt card, and
// that the answer decides the outcome. A THROWAWAY daemon (fresh state dir, its own port, INFANTRY_PORT_STRICT) and a
// THROWAWAY git repo; refuses 8787 / 8788; never reads or writes ~/.infantry.
// Needs `gradlew :superiorSoftLibrary:infantry:installDist -x verifyBase` first.
//
//   node docs/infantry-e2e-prompt-ask.mjs [--port 18799] [--model opencode-go/gpt-6-luna] [--keep] [--timeout 300000]
//
// Turn 1 asks the agent to create ALLOWED.txt; every prompt_request is answered allow_once  -> the file must exist.
// Turn 2 asks it to create DENIED.txt;  every prompt_request is answered deny        -> the file must NOT exist, and
// the chat notice says "not approved at the prompt" (never "can't prompt").
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, copyFileSync, readdirSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1]?.startsWith('--') || all[i + 1] === undefined ? 'true' : all[i + 1]]);
  return acc;
}, []));
const PORT = Number(args.port ?? 18799);
const TURN_MS = Number(args.timeout ?? 300000);
const MODEL = args.model ?? 'opencode-go/gpt-6-luna';
if ([8787, 8788].includes(PORT)) { console.error('refusing port', PORT, "(the owner's daemons)"); process.exit(2); }

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const install = join(repoRoot, 'superiorSoftLibrary/infantry/build/install/infantry');
if (!existsSync(join(install, 'lib'))) { console.error('no installDist at', install); process.exit(2); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const git = (cwd, ...a) => spawnSync('git', a, { cwd, encoding: 'utf8' });

const state = mkdtempSync(join(tmpdir(), 'infantry-e2e-ask-'));
mkdirSync(join(state, 'harnesses'));
for (const f of readdirSync(join(repoRoot, 'superiorSoftLibrary/infantry/docs/harnesses')).filter(f => f.endsWith('.json'))) {
  copyFileSync(join(repoRoot, 'superiorSoftLibrary/infantry/docs/harnesses', f), join(state, 'harnesses', f));
}
const project = join(state, 'project');
mkdirSync(project);
writeFileSync(join(project, 'README.md'), '# e2e ask scratch project\n');
git(project, 'init', '-q', '-b', 'main');
git(project, '-c', 'user.name=e2e', '-c', 'user.email=e2e@localhost', 'add', 'README.md');
git(project, '-c', 'user.name=e2e', '-c', 'user.email=e2e@localhost', 'commit', '-q', '-m', 'init');

const log = [];
for (const k of ['INFANTRY_TOKEN', 'INFANTRY_TOKEN_FILE', 'INFANTRY_REPO', 'INFANTRY_MAIL_BASE', 'INFANTRY_SESSION_ID',
  'INFANTRY_URL', 'INFANTRY_PROJECT_ID', 'INFANTRY_AGENT_ID', 'INFANTRY_ROLE', 'INFANTRY_BASE_BRANCH', 'INFANTRY_TOOL_APPROVAL']) delete process.env[k];
const URL = `ws://127.0.0.1:${PORT}/ws`;
const daemon = spawn('java', ['-cp', join(install, 'lib', '*'), 'com.superiorsoftllc.infantry.daemon.DaemonMainKt'], {
  cwd: project,
  env: {
    ...process.env,
    INFANTRY_STATE_DIR: state, INFANTRY_HOME: state, INFANTRY_PORT: String(PORT), INFANTRY_PORT_STRICT: 'true',
    INFANTRY_PROVIDER: 'opencode', INFANTRY_TOOL_APPROVAL: 'ask', INFANTRY_REPO: project, INFANTRY_URL: URL,
  },
});
daemon.stdout.on('data', d => log.push(String(d)));
daemon.stderr.on('data', d => log.push(String(d)));

const frames = [];
let waiters = [];
let ws;
async function connect() {
  for (let i = 0; i < 120; i++) {
    try {
      const sock = new WebSocket(URL);
      await new Promise((ok, ko) => { sock.onopen = ok; sock.onerror = ko; });
      return sock;
    } catch { await sleep(1000); }
  }
  throw new Error('daemon never answered on ' + PORT);
}
const send = (type, data) => ws.send(JSON.stringify({ type, data: typeof data === 'string' ? data : JSON.stringify(data) }));

let sid = null;
let answer = 'allow_once';
const prompts = []; // { phase, tool, input, decision }
let phase = 'allow';

/** Resolves when this session's turn ends (`finished`), or 'timeout'. Every prompt_request is answered with [answer]. */
function turnEnd() {
  return new Promise(res => {
    const timer = setTimeout(() => res('timeout'), TURN_MS);
    waiters.push(f => {
      if (f.type === 'error' && !f.sessionId) { clearTimeout(timer); res(`refused: ${f.data}`); return true; }
      if (f.type === 'finished' && f.sessionId === sid) { clearTimeout(timer); res('finished'); return true; }
      return false;
    });
  });
}

function onFrame(f) {
  if (f.sessionId && !sid && f.type === 'stdout') sid = f.sessionId;
  if (f.type === 'prompt_request') {
    const req = JSON.parse(f.data);
    if (req.kind !== 'tool_approval') return;
    sid ??= req.sessionId;
    prompts.push({ phase, tool: req.tool, input: req.input, decision: answer });
    console.log(`  prompt_request ${req.tool} "${(req.input ?? '').slice(0, 80)}" -> ${answer}`);
    send('prompt_response', { promptId: req.promptId, decision: answer });
  }
}

const results = [];
let failed = false;
const check = (ok, what) => { results.push(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failed = true; };

try {
  ws = await connect();
  ws.onmessage = ev => {
    let f; try { f = JSON.parse(ev.data); } catch { return; }
    frames.push(f);
    onFrame(f);
    waiters = waiters.filter(w => !w(f));
  };

  const ask = file => `Create a new file named ${file} in the current working directory containing exactly the text: ${file} written. ` +
    'Use your file write/edit tool, not the shell. If the tool call is rejected, do not retry and do not try another way; just reply "rejected".';

  // Turn 1: allow.
  console.log(`turn 1 (allow_once), model ${MODEL}`);
  send('new', { provider: 'opencode', model: MODEL, toolApproval: 'ask' });
  await sleep(500);
  let end = turnEnd();
  send('prompt', ask('ALLOWED.txt'));
  const end1 = await end;
  const sess = JSON.parse(await new Promise(res => { waiters.push(f => f.type === 'sessions' && (res(f.data), true)); send('sessions', ''); }));
  const rec = (Array.isArray(sess) ? sess : sess.sessions ?? []).find(s => s.id === sid);
  const cwd = rec?.workingDir ?? project;
  console.log(`  turn 1 ${end1}; session ${sid}; cwd ${cwd}`);
  const allowPrompts = prompts.filter(p => p.phase === 'allow');
  check(allowPrompts.length > 0, `turn 1 opened a prompt card (${allowPrompts.map(p => p.tool).join(', ') || 'none'})`);
  check(existsSync(join(cwd, 'ALLOWED.txt')), `allow_once -> ALLOWED.txt exists${existsSync(join(cwd, 'ALLOWED.txt')) ? ` ("${readFileSync(join(cwd, 'ALLOWED.txt'), 'utf8').trim()}")` : ''}`);

  // Turn 2: deny, same session (same opencode serve process).
  phase = 'deny';
  answer = 'deny';
  console.log('turn 2 (deny)');
  end = turnEnd();
  send('resume', `${sid}\n${ask('DENIED.txt')}`);
  const end2 = await end;
  console.log(`  turn 2 ${end2}`);
  const denyPrompts = prompts.filter(p => p.phase === 'deny');
  check(denyPrompts.length > 0, `turn 2 opened a prompt card (${denyPrompts.map(p => p.tool).join(', ') || 'none'})`);
  check(!existsSync(join(cwd, 'DENIED.txt')), 'deny -> DENIED.txt does not exist');
  const notices = frames.filter(f => f.sessionId === sid && f.type === 'stderr' && /\[infantry\] .*denied tool /.test(String(f.data))).map(f => String(f.data));
  check(notices.some(n => n.includes('not approved at the prompt')), `deny notice names the prompt: ${notices.join(' | ') || '(none)'}`);
  check(!notices.some(n => n.includes("can't prompt")), 'no "(this harness can\'t prompt)" notice on serve');
  check(prompts.every(p => !/^infantry\b/.test(p.input ?? '')), 'no infantry command was prompted');
  send('kill', sid);
} catch (e) {
  check(false, `script error: ${e.message}`);
} finally {
  if (args.keep) writeFileSync(join(state, 'frames.json'), JSON.stringify(frames, null, 1));
  writeFileSync(join(state, 'daemon.log'), log.join(''));
  try { ws?.close(); } catch {}
  if (process.platform === 'win32') spawnSync('taskkill', ['/T', '/F', '/PID', String(daemon.pid)]);
  else daemon.kill();
}
console.log('\n' + results.join('\n'));
console.log(`prompts: ${JSON.stringify(prompts)}`);
console.log(args.keep ? `state kept: ${state}` : '');
if (!args.keep) { await sleep(1500); try { rmSync(state, { recursive: true, force: true }); } catch {} }
process.exit(failed ? 1 : 0);
