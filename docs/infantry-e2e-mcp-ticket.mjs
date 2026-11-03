#!/usr/bin/env node
// MCP-1: live check that a Claude session the daemon spawns has Infantry's own MCP server (`infantry mcp serve`) and can
// create a ticket through the `mcp__infantry__infantry_ticket_create` tool in Tool approval DENY -- not through the shell --
// with a multi-line description and role / provider / model pins, stored exactly.
// A THROWAWAY daemon (fresh state dir, its own port, INFANTRY_PORT_STRICT) and a THROWAWAY git repo; refuses 8787 / 8788;
// never reads or writes ~/.infantry. INFANTRY_CLAUDE_STRICT_MCP=true, so the infantry entry is the ONLY MCP server loaded
// (the user's own servers stay out of it, and nothing else could have answered).
// Needs `gradlew :superiorSoftLibrary:infantry:installDist -x verifyBase` first.
//
//   node docs/infantry-e2e-mcp-ticket.mjs [--port 18797] [--model haiku] [--keep] [--timeout 300000]
import { spawn, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const args = Object.fromEntries(process.argv.slice(2).reduce((acc, a, i, all) => {
  if (a.startsWith('--')) acc.push([a.slice(2), all[i + 1]?.startsWith('--') || all[i + 1] === undefined ? 'true' : all[i + 1]]);
  return acc;
}, []));
const PORT = Number(args.port ?? 18797);
const TURN_MS = Number(args.timeout ?? 300000);
const MODEL = args.model ?? 'haiku';
if ([8787, 8788].includes(PORT)) { console.error('refusing port', PORT, "(the owner's daemons)"); process.exit(2); }

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const install = join(repoRoot, 'superiorSoftLibrary/infantry/build/install/infantry');
if (!existsSync(join(install, 'lib'))) { console.error('no installDist at', install); process.exit(2); }
const sleep = ms => new Promise(r => setTimeout(r, ms));
const git = (cwd, ...a) => spawnSync('git', a, { cwd, encoding: 'utf8' });

const state = mkdtempSync(join(tmpdir(), 'infantry-e2e-mcp-'));
const project = join(state, 'project');
mkdirSync(project);
writeFileSync(join(project, 'README.md'), '# e2e mcp scratch project\n');
git(project, 'init', '-q', '-b', 'main');
git(project, '-c', 'user.name=e2e', '-c', 'user.email=e2e@localhost', 'add', 'README.md');
git(project, '-c', 'user.name=e2e', '-c', 'user.email=e2e@localhost', 'commit', '-q', '-m', 'init');

const log = [];
for (const k of ['INFANTRY_TOKEN', 'INFANTRY_TOKEN_FILE', 'INFANTRY_REPO', 'INFANTRY_MAIL_BASE', 'INFANTRY_SESSION_ID', 'INFANTRY_BIN',
  'INFANTRY_URL', 'INFANTRY_PROJECT_ID', 'INFANTRY_AGENT_ID', 'INFANTRY_ROLE', 'INFANTRY_BASE_BRANCH', 'INFANTRY_TOOL_APPROVAL',
  'INFANTRY_CLAUDE_MCP_CONFIG', 'INFANTRY_CLAUDE_PERMISSION_MODE']) delete process.env[k];
const URL = `ws://127.0.0.1:${PORT}/ws`;
const daemon = spawn('java', ['-cp', join(install, 'lib', '*'), 'com.superiorsoftllc.infantry.daemon.DaemonMainKt'], {
  cwd: project,
  env: {
    ...process.env,
    INFANTRY_STATE_DIR: state, INFANTRY_HOME: state, INFANTRY_PORT: String(PORT), INFANTRY_PORT_STRICT: 'true',
    INFANTRY_PROVIDER: 'claude', INFANTRY_TOOL_APPROVAL: 'deny', INFANTRY_REPO: project, INFANTRY_URL: URL,
    INFANTRY_CLAUDE_STRICT_MCP: 'true',
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
const next = (pred, ms = 15000) => new Promise((res, rej) => {
  const t = setTimeout(() => rej(new Error('timeout')), ms);
  waiters.push(f => (pred(f) ? (clearTimeout(t), res(f), true) : false));
});

let sid = null;
const results = [];
let failed = false;
const check = (ok, what) => { results.push(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failed = true; };

const TITLE = `MCP-1 live ticket ${Date.now()}`;
const DESCRIPTION = [
  '## MCP-1 live check',
  '',
  '1. created through the infantry MCP tool',
  '   - indented line with "double quotes" and `backticks`',
  "2. shell metacharacters: & | ; $(not-run) 'single'",
  '',
  'Last line.',
].join('\n');
const PINS = { role: 'developer', provider: 'claude', model: 'claude-opus-5-5' };

try {
  ws = await connect();
  ws.onmessage = ev => {
    let f; try { f = JSON.parse(ev.data); } catch { return; }
    frames.push(f);
    if (f.sessionId && !sid && f.type === 'stdout') sid = f.sessionId;
    waiters = waiters.filter(w => !w(f));
  };

  const prompt = [
    'Call the MCP tool mcp__infantry__infantry_ticket_create exactly once with these arguments. Do NOT use Bash,',
    'PowerShell or any shell command, and do not call any other tool.',
    `title: ${TITLE}`,
    `role: ${PINS.role}`,
    `provider: ${PINS.provider}`,
    `model: ${PINS.model}`,
    'description: the text between the BEGIN and END lines below, byte for byte, with its line breaks (not the BEGIN/END lines):',
    'BEGIN',
    DESCRIPTION,
    'END',
    'Then reply with only the ticketId the tool returned.',
  ].join('\n');

  console.log(`claude session, model ${MODEL}, Tool approval deny, port ${PORT}`);
  send('new', { provider: 'claude', model: MODEL, toolApproval: 'deny' });
  await sleep(500);
  const finished = next(f => (f.type === 'error' && !f.sessionId) || (f.type === 'finished' && f.sessionId === sid), TURN_MS)
    .then(f => (f.type === 'finished' ? 'finished' : `refused: ${f.data}`)).catch(() => 'timeout');
  send('prompt', prompt);
  const end = await finished;
  console.log(`  turn ${end}; session ${sid}`);
  check(end === 'finished', `the turn finished (${end})`);

  // What claude did: tool_use blocks in its stream-json stdout.
  const toolUses = [];
  for (const f of frames.filter(f => f.sessionId === sid && f.type === 'stdout')) {
    let line; try { line = JSON.parse(f.data); } catch { continue; }
    for (const c of line?.message?.content ?? []) if (c?.type === 'tool_use') toolUses.push({ name: c.name, input: c.input });
  }
  console.log(`  tool calls: ${toolUses.map(t => t.name).join(', ') || '(none)'}`);
  check(toolUses.some(t => t.name === 'mcp__infantry__infantry_ticket_create'), 'claude called mcp__infantry__infantry_ticket_create');
  check(!toolUses.some(t => /^(Bash|PowerShell)$/.test(t.name)), 'no shell tool was used');
  const denials = frames.filter(f => f.sessionId === sid && /denied tool/.test(String(f.data))).map(f => String(f.data));
  check(denials.length === 0, `no tool was denied in deny mode (${denials.join(' | ') || 'none'})`);

  // Read it back from the daemon's own board, over the wire.
  const listed = next(f => f.type === 'tickets');
  send('ticket_list', '');
  const board = JSON.parse((await listed).data);
  const t = board.find(x => x.title === TITLE);
  check(!!t, `the ticket is on the board (${t?.id ?? 'missing'})`);
  if (t) {
    check(t.description === DESCRIPTION, `description stored exactly (${JSON.stringify(t.description)})`);
    check(t.agentId === `role:${PINS.role}`, `role pin: ${t.agentId}`);
    check(t.provider === PINS.provider, `provider pin: ${t.provider}`);
    check(t.model === PINS.model, `model pin: ${t.model}`);
    check(t.status === 'todo', `status: ${t.status}`);
  }
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
console.log(args.keep ? `state kept: ${state}` : '');
if (!args.keep) { await sleep(1500); try { rmSync(state, { recursive: true, force: true }); } catch {} }
process.exit(failed ? 1 : 0);
