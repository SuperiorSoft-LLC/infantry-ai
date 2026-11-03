#!/usr/bin/env node
// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// E2E-TASK-1: an agent creates a ticket with the `infantry` CLI, the ticket is assigned a role / harness / model and started
// the way the UI starts it (`ticket_move` -> in_progress), and we check the spawned session did the work.
// See docs/infantry-e2e-task.md.
//
// Safety: a THROWAWAY daemon (fresh state dir, its own port, INFANTRY_PORT_STRICT) and a THROWAWAY git repo as
// INFANTRY_REPO. It refuses 8787 / 8788 and never reads or writes ~/.infantry.
// Needs `gradlew :superiorSoftLibrary:infantry:installDist -x verifyBase` first.
//
//   node docs/infantry-e2e-task.mjs [--port 18798] [--only opencode,claude] [--keep] [--timeout 300000]
//                                  [--mode deny|allowEdits|allowAll] [--work-mode deny|allowEdits|allowAll]
// --mode (default allowAll) is the Tool approval of the CREATOR session and the daemon default; --work-mode (default
// --mode) overrides the daemon default only, i.e. what the ticket's own session runs under (BUG-PERM-5).
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, copyFileSync, readdirSync, writeFileSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1]?.startsWith('--') || all[i + 1] === undefined ? 'true' : all[i + 1]]);
  return acc;
}, []));
const PORT = Number(args.port ?? 18798);
const TURN_MS = Number(args.timeout ?? 300000);
const MODES = ['deny', 'allowEdits', 'allowAll'];
const MODE = args.mode ?? 'allowAll';
const WORK_MODE = args['work-mode'] ?? MODE;
for (const m of [MODE, WORK_MODE]) if (!MODES.includes(m)) { console.error('unknown Tool approval', m, '- one of', MODES.join(', ')); process.exit(2); }
console.log(`Tool approval: creator ${MODE}, ticket session (daemon default) ${WORK_MODE}`);
if ([8787, 8788].includes(PORT)) { console.error('refusing port', PORT, '(the owner\'s daemons)'); process.exit(2); }

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const install = join(repoRoot, 'superiorSoftLibrary/infantry/build/install/infantry');
if (!existsSync(join(install, 'lib'))) { console.error('no installDist at', install); process.exit(2); }

// One row per harness: the cheapest model (Luna 6 for OpenCode, haiku for Claude). The profile every ticket is
// assigned to pins the DAEMON DEFAULT (opencode + Luna 6), so a session on anything else proves the ticket pin won.
const DEFAULT = { provider: 'opencode', model: 'opencode-go/gpt-6-luna' };
const ROLE = 'developer';
const CASES = [
  { provider: 'opencode', model: 'opencode-go/gpt-6-luna', cli: 'opencode' },
  { provider: 'claude', model: 'haiku', cli: 'claude' },
  { provider: 'pi', model: 'commandcode/deepseek/deepseek-v4-flash', cli: 'pi' },
  { provider: 'codex', model: null, cli: 'codex' }, // codex's own config.toml default
  { provider: 'gemini', model: null, cli: 'gemini' },
].filter(c => !args.only || args.only.split(',').includes(c.provider));

const onPath = cli => spawnSync(process.platform === 'win32' ? 'where' : 'which', [cli], { encoding: 'utf8' }).status === 0;
const sleep = ms => new Promise(r => setTimeout(r, ms));
const git = (cwd, ...a) => spawnSync('git', a, { cwd, encoding: 'utf8' });

// ---- throwaway state dir, harness descriptors, project repo ---------------------------------------------------------
const state = mkdtempSync(join(tmpdir(), 'infantry-e2e-task-'));
mkdirSync(join(state, 'harnesses'));
for (const f of readdirSync(join(repoRoot, 'superiorSoftLibrary/infantry/docs/harnesses')).filter(f => f.endsWith('.json'))) {
  copyFileSync(join(repoRoot, 'superiorSoftLibrary/infantry/docs/harnesses', f), join(state, 'harnesses', f));
}
const project = join(state, 'project');
mkdirSync(project);
writeFileSync(join(project, 'README.md'), '# e2e scratch project\n');
git(project, 'init', '-q', '-b', 'main');
git(project, '-c', 'user.name=e2e', '-c', 'user.email=e2e@localhost', 'add', 'README.md');
git(project, '-c', 'user.name=e2e', '-c', 'user.email=e2e@localhost', 'commit', '-q', '-m', 'init');

// ---- daemon ---------------------------------------------------------------------------------------------------------
const log = [];
const sep = process.platform === 'win32' ? ';' : ':';
for (const k of ['INFANTRY_TOKEN', 'INFANTRY_TOKEN_FILE', 'INFANTRY_REPO', 'INFANTRY_MAIL_BASE', 'INFANTRY_SESSION_ID',
  'INFANTRY_URL', 'INFANTRY_PROJECT_ID', 'INFANTRY_AGENT_ID', 'INFANTRY_ROLE', 'INFANTRY_BASE_BRANCH']) delete process.env[k];
const URL = `ws://127.0.0.1:${PORT}/ws`;
const daemon = spawn('java', ['-cp', join(install, 'lib', '*'), 'com.superiorsoftllc.infantry.daemon.DaemonMainKt'], {
  cwd: state,
  env: {
    ...process.env,
    INFANTRY_STATE_DIR: state, INFANTRY_HOME: state, INFANTRY_PORT: String(PORT), INFANTRY_PORT_STRICT: 'true',
    INFANTRY_PROVIDER: DEFAULT.provider, INFANTRY_PROVIDERS: 'claude', INFANTRY_TOOL_APPROVAL: WORK_MODE,
    INFANTRY_REPO: project, INFANTRY_URL: URL,
    INFANTRY_BIN: join(install, 'bin'),
    PATH: join(install, 'bin') + sep + process.env.PATH,
  },
});
daemon.stdout.on('data', d => log.push(String(d)));
daemon.stderr.on('data', d => log.push(String(d)));

// ---- one long-lived socket: every frame the daemon pushes is kept, grouped by session -------------------------------
const frames = [];
let waiters = [];
async function connect() {
  for (let i = 0; i < 120; i++) {
    try {
      const ws = new WebSocket(URL);
      await new Promise((ok, ko) => { ws.onopen = ok; ws.onerror = ko; });
      return ws;
    } catch { await sleep(1000); }
  }
  throw new Error('daemon never answered on ' + PORT);
}
let ws;
function attach(sock) {
  sock.onmessage = ev => {
    let f; try { f = JSON.parse(ev.data); } catch { return; }
    f.at = Date.now();
    frames.push(f);
    waiters = waiters.filter(w => !w(f));
  };
}
/** Sends one frame and resolves with the first later frame of one of [types] (or an `error` with no session). */
function request(type, data, types, ms = 30000) {
  return new Promise((ok, ko) => {
    const timer = setTimeout(() => ko(new Error(`${type}: no ${types} within ${ms / 1000}s`)), ms);
    waiters.push(f => {
      if (types.includes(f.type) || (f.type === 'error' && !f.sessionId)) {
        clearTimeout(timer);
        f.type === 'error' && !types.includes('error') ? ko(new Error(`${type} refused: ${f.data}`)) : ok(f);
        return true;
      }
      return false;
    });
    ws.send(JSON.stringify({ type, data: typeof data === 'string' ? data : JSON.stringify(data) }));
  });
}
const tickets = async () => JSON.parse((await request('ticket_list', '', ['tickets'])).data);
const sessions = async () => JSON.parse((await request('sessions', '', ['sessions'])).data);
const textOf = sid => frames.filter(f => f.sessionId === sid && (f.type === 'stdout' || f.type === 'stderr')).map(f => String(f.data)).join('\n');
const denialsOf = sid => frames.filter(f => f.sessionId === sid && f.type === 'stderr' && /\[infantry\] .*denied tool /.test(String(f.data))).map(f => String(f.data));

// ---- one matrix row -------------------------------------------------------------------------------------------------
async function runCase(c) {
  const row = { harness: c.provider, model: c.model ?? '(CLI default)', created: 'no', startedOn: '-', file: '-', state: '-', secs: 0, notes: [] };
  if (!onPath(c.cli)) { row.result = 'unverified'; row.notes.push(`${c.cli} is not installed`); return row; }
  const t0 = Date.now();
  const tag = `${c.provider}-${t0.toString(36)}`;
  const expected = `hello from ${c.provider}/${c.model ?? 'default'}`;
  const title = `E2E HELLO ${tag}`;
  const desc = `Create file HELLO.md in the project root containing exactly: ${expected}`;

  // The role's profile pins the DAEMON DEFAULT (opencode + Luna 6), so a session on anything else proves the ticket pin won.
  // It exists before the ticket so `--role developer` (agentId role:developer) resolves to it at start.
  await request('agent_save', { name: `e2e ${tag}`, role: ROLE, provider: DEFAULT.provider, model: DEFAULT.model }, ['agent']);

  // 2. CREATE + ASSIGN, from inside an agent session on this harness: the agent runs the CLI with --role / --provider /
  // --model (BUG-E2E-1), the daemon is never driven here.
  const pins = `--role ${ROLE} --provider ${c.provider}${c.model ? ` --model ${c.model}` : ''}`;
  // `new` has no answer: it arms the spawn options, and the next prompt creates the session (or `error` refuses it).
  let creatorId = null;
  const finished = new Promise(res => {
    const timer = setTimeout(() => res('timeout'), TURN_MS);
    waiters.push(f => {
      if (f.type === 'error' && !f.sessionId) { clearTimeout(timer); res(`refused: ${f.data}`); return true; }
      if (f.sessionId && !creatorId && f.type !== 'tickets' && f.type !== 'ticket') creatorId = f.sessionId;
      if (f.type === 'finished' && f.sessionId === creatorId) { clearTimeout(timer); res('finished'); return true; }
      return false;
    });
  });
  ws.send(JSON.stringify({ type: 'new', data: JSON.stringify({ provider: c.provider, model: c.model ?? undefined, toolApproval: MODE }) }));
  await sleep(500);
  ws.send(JSON.stringify({ type: 'prompt', data:
    'Use your shell tool to run exactly this one command and nothing else, then reply with the line it printed:\n' +
    `infantry ticket create --title "${title}" --desc "${desc}" ${pins}` }));
  const creatorEnd = await finished;
  ws.send(JSON.stringify({ type: 'new', data: '' })); // detach: the next frames on this socket are not prompts for it
  let ticket = (await tickets()).find(t => t.title === title);
  if (!ticket) {
    row.result = 'FAIL';
    const said = textOf(creatorId).split('\n').filter(l => /created|error|unknown|refused/i.test(l)).slice(-2).join(' / ');
    row.notes.push(`creator ${creatorEnd}: no ticket "${title}" on the board${said ? ` (${said.slice(0, 160)})` : ''}`);
    row.secs = Math.round((Date.now() - t0) / 1000);
    return row;
  }
  row.created = 'yes';
  if (ticket.description.trim() !== desc) row.notes.push(`description differs: ${JSON.stringify(ticket.description).slice(0, 120)}`);
  // The assignment came from the CLI flags alone: read it back off the board, never from what the agent printed.
  const assigned = ticket.agentId === `role:${ROLE}` && ticket.provider === c.provider && (ticket.model ?? null) === c.model;
  if (!assigned) {
    row.result = 'FAIL';
    row.notes.push(`CLI pins not stored: agentId=${ticket.agentId} provider=${ticket.provider} model=${ticket.model ?? '(none)'}`);
    row.secs = Math.round((Date.now() - t0) / 1000);
    return row;
  }

  // 3. DEPLOY the way the UI does: move to in_progress.
  const startAnswer = JSON.parse((await request('ticket_move', { id: ticket.id, status: 'in_progress' }, ['ticket'], 120000)).data);
  if (!startAnswer.start?.ok) {
    row.result = 'FAIL';
    row.state = startAnswer.status;
    row.notes.push(`start ${startAnswer.start?.reason}: ${startAnswer.start?.detail ?? ''}`);
    row.secs = Math.round((Date.now() - t0) / 1000);
    return row;
  }
  const sid = startAnswer.sessionId;

  // Wait for the ticket's run to end (lastRun.endedAt), polling the board.
  let t = startAnswer;
  while (Date.now() - t0 < TURN_MS * 2) {
    await sleep(3000);
    t = (await tickets()).find(x => x.id === ticket.id);
    if (t?.lastRun?.endedAt) break;
  }
  await sleep(1500);
  const rec = (await sessions()).find(s => s.id === sid) ?? {};
  row.startedOn = `${rec.provider ?? '?'}/${rec.model ?? '(default)'}`;
  const pinOk = rec.provider === c.provider && (c.model == null || rec.model === c.model);
  if (!pinOk) row.notes.push(`session ran on ${row.startedOn}, ticket asked ${c.provider}/${c.model ?? 'default'}`);
  if (!t?.lastRun?.endedAt) row.notes.push(`run did not end within ${TURN_MS * 2 / 1000}s`);
  else row.notes.push(`run ${t.lastRun.outcome}${t.lastRun.detail ? ': ' + t.lastRun.detail.slice(0, 120) : ''}`);

  // 4. VERIFY: HELLO.md in the ticket's worktree, the ticket's state, the transcript.
  const wt = rec.worktreePath ?? worktreeOf(t?.branch);
  const hello = wt ? join(wt, 'HELLO.md') : null;
  if (!hello || !existsSync(hello)) row.file = `missing (${wt ?? 'no worktree'})`;
  else {
    const body = readFileSync(hello, 'utf8');
    row.file = body.replace(/\r?\n$/, '') === expected ? 'exact' : `wrong: ${JSON.stringify(body).slice(0, 80)}`;
  }
  row.state = t?.status ?? '?';
  const denials = denialsOf(sid);
  const transcript = textOf(sid);
  if (denials.length) row.notes.push(`denial: ${denials[0].slice(0, 140)}`);
  if (!transcript) row.notes.push('no stdout/stderr frames for the ticket session reached this client');
  row.transcriptChars = transcript.length;
  row.secs = Math.round((Date.now() - t0) / 1000);
  row.result = row.created === 'yes' && pinOk && row.file === 'exact' && denials.length === 0 ? 'PASS' : 'FAIL';
  if (row.result === 'PASS' && !['done', 'pending'].includes(row.state)) row.notes.push(`ticket left in ${row.state} (no done/review transition)`);
  writeFileSync(join(state, `transcript-${tag}.txt`), transcript);
  try { ws.send(JSON.stringify({ type: 'kill', data: sid })); ws.send(JSON.stringify({ type: 'kill', data: creatorId })); } catch {}
  return row;
}

function worktreeOf(branch) {
  if (!branch) return null;
  const out = git(project, 'worktree', 'list', '--porcelain').stdout.split(/\r?\n\r?\n/);
  const hit = out.find(b => b.includes(`branch refs/heads/${branch}`));
  return hit?.match(/^worktree (.+)$/m)?.[1] ?? null;
}

const results = [];
try {
  ws = await connect();
  attach(ws);
  for (const c of CASES) {
    process.stderr.write(`[e2e] ${c.provider} ${c.model ?? '(default)'} ...\n`);
    try { results.push(await runCase(c)); }
    catch (e) { results.push({ harness: c.provider, model: c.model ?? '(CLI default)', result: 'FAIL', created: '?', startedOn: '-', file: '-', state: '-', secs: 0, notes: [String(e.message ?? e)] }); }
    process.stderr.write(`[e2e]   -> ${results.at(-1).result}\n`);
  }
} finally {
  try { ws?.close(); } catch {}
  // The WHOLE tree: on Windows a plain kill orphans the sessions' children (an `opencode serve` survives its daemon).
  if (process.platform === 'win32') spawnSync('taskkill', ['/T', '/F', '/PID', String(daemon.pid)]);
  else daemon.kill();
  writeFileSync(join(state, 'daemon.log'), log.join(''));
  writeFileSync(join(state, 'frames.json'), JSON.stringify(frames, null, 1));
}

const table = ['| harness | model | created? | started on | HELLO.md | ticket state | time | result | notes |',
  '| --- | --- | --- | --- | --- | --- | --- | --- | --- |',
  ...results.map(r => `| ${r.harness} | ${r.model} | ${r.created} | ${r.startedOn} | ${r.file} | ${r.state} | ${r.secs}s | ${r.result} | ${r.notes.join('; ').replace(/\|/g, '\\|')} |`)].join('\n');
console.log(table);
console.log(`\nstate dir: ${state}${args.keep ? ' (kept)' : ' (removed; --keep to inspect)'}`);
if (!args.keep) { await sleep(1500); try { rmSync(state, { recursive: true, force: true }); } catch {} }
process.exit(results.some(r => r.result === 'FAIL') ? 1 : 0);
