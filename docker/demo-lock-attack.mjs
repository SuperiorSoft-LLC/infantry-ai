// DEMO-LOCK attacker test (ticket 2f154f5f). Node >= 22 (global WebSocket/fetch); no dependencies.
//
//   DEMO_URL=http://127.0.0.1:8813 DEMO_TOKEN_FILE=<file with the OWNER token> node docker/demo-lock-attack.mjs [--scenarios]
//
// 1. Generates, FROM THE SOURCE, every WebSocket frame type the daemon's connection loop handles (the `when` arms of
//    handleConnection in DaemonMain.kt, names resolved through protocol/Protocol.kt) and every HTTP route (get/post/...
//    calls under daemon/), then sends each with the OWNER token against a locked daemon and prints the full table.
//    Every frame is sent naming a SEEDED session (the worst case for the lock) with a junk payload. A frame counts as
//    DENIED when the daemon answers it with an error starting "demo:"; anything else (an answer, another error, silence)
//    means it passed the lock. The script fails (exit 1) when anything outside the allow-list passed.
// 2. --scenarios: the named attacks (project, third session, sandbox flip, model allowlist, paid model, token read).
import fs from 'node:fs'
import path from 'node:path'
import url from 'node:url'

const here = path.dirname(url.fileURLToPath(import.meta.url))
const src = path.join(here, '..', 'superiorSoftLibrary', 'agentHq', 'src', 'main', 'kotlin', 'com', 'superiorsoftllc', 'agenthq')
const base = (process.env.DEMO_URL || 'http://127.0.0.1:8813').replace(/\/$/, '')
// DEMO_TOKEN_FILE, not AGENTHQ_TOKEN: an agent shell already carries ITS daemon's token under that name.
const token = fs.readFileSync(process.env.DEMO_TOKEN_FILE, 'utf8').trim()
const scenarios = process.argv.includes('--scenarios')

// ---- frame types from the code -------------------------------------------------------------------------------------
const protocol = fs.readFileSync(path.join(src, 'protocol', 'Protocol.kt'), 'utf8')
const consts = Object.fromEntries([...protocol.matchAll(/const val ([A-Z0-9_]+) = "([^"]+)"/g)].map(m => [m[1], m[2]]))
const daemon = fs.readFileSync(path.join(src, 'daemon', 'DaemonMain.kt'), 'utf8')
const start = daemon.indexOf('private suspend fun handleConnection(')
const end = daemon.indexOf('\nprivate fun ', start + 1)
const body = daemon.slice(start, end > 0 ? end : undefined)
const frameTypes = new Set()
for (const line of body.split('\n')) {
  const m = line.match(/^\s*((?:Envelope\.[A-Z0-9_]+\s*,\s*)*Envelope\.[A-Z0-9_]+)\s*(?:->|,\s*(?:\/\/.*)?$)/)
  if (!m) continue
  for (const name of m[1].match(/[A-Z0-9_]+(?=\s*(,|$))/g) || []) if (consts[name]) frameTypes.add(consts[name])
}
for (const m of body.matchAll(/envelope\.type == ([A-Z_]+_TYPE)/g)) {
  const lit = daemon.match(new RegExp(`const val ${m[1]} = "([^"]+)"`)); if (lit) frameTypes.add(lit[1])
}
// ---- HTTP routes from the code -------------------------------------------------------------------------------------
const routes = []
for (const f of fs.readdirSync(path.join(src, 'daemon'))) {
  const text = fs.readFileSync(path.join(src, 'daemon', f), 'utf8')
  for (const m of text.matchAll(/\b(get|post|put|delete|patch|webSocket)\("(\/[^"]*)"/g)) routes.push([m[1], m[2], f])
}

// Reads of ONE project (DemoLock.PROJECT_READ_FRAMES): allowed only when the payload names the demo project.
const PROJECT_READS = ['project_git_status', 'project_git_log', 'project_git_diff', 'project_git_branches', 'project_git_branch_changes',
  'project_git_branch_diff', 'project_git_worktrees', 'project_git_worktree_status', 'epic_list', 'memory_list', 'memory_search', 'memory_get', 'pipeline_show']
const ALLOWED_FRAMES = new Set(['sessions', 'project_list', 'project_get', 'workspace_list', 'settings_get', 'limit_catalog', 'limits',
  'usage_get', 'ticket_list', 'trigger_list', 'command_list', 'role_list', 'providers', 'models', 'session_stats_get', 'question_list',
  'mail_inbox', 'mail_outbox', 'mail_thread', 'mail_threads', 'mail_waiting', 'mail_read',
  'agent_list', 'role_binding_list', 'trait_list', 'role_traits_list', 'attention_list', 'provider_states', 'prompt_list', 'skill_list',
  ...PROJECT_READS,
  'replay_request', 'prompt', 'resume', 'interrupt', 'kill', 'mail_send'])
const ALLOWED_HTTP = new Set(['GET /health', 'GET /attention', 'GET /ws', 'GET /', 'POST /mail/send', 'POST /mail/read', 'GET /mail/inbox',
  'GET /mail/outbox', 'GET /mail/waiting', 'GET /mail/thread/{threadId}'])

// ---- one WebSocket with the owner token ----------------------------------------------------------------------------
function connect() {
  // undici (Node) accepts headers: the CLI-style Authorization bearer, no Origin (the browser path is the UI's).
  const ws = new WebSocket(base.replace(/^http/, 'ws') + '/ws', { headers: { Authorization: 'Bearer ' + token } })
  const frames = []
  const waiters = []
  ws.onmessage = ev => {
    const f = JSON.parse(ev.data); frames.push(f)
    for (const w of [...waiters]) if (w.test(f)) { waiters.splice(waiters.indexOf(w), 1); w.resolve(f) }
  }
  const wait = (test, ms) => new Promise(resolve => {
    const hit = frames.find(test); if (hit) return resolve(hit)
    const w = { test, resolve }; waiters.push(w)
    setTimeout(() => { const i = waiters.indexOf(w); if (i >= 0) { waiters.splice(i, 1); resolve(null) } }, ms)
  })
  return new Promise((resolve, reject) => {
    ws.onerror = e => reject(new Error('ws error ' + (e.message || '')))
    ws.onopen = async () => { await wait(f => f.type === 'sessions', 5000); resolve({ ws, frames, wait }) }
  })
}

let rid = 0
async function send(c, type, data, sessionId, ms = 1500) {
  const requestId = 'atk-' + (++rid)
  c.ws.send(JSON.stringify({ type, data, sessionId, requestId }))
  const f = await c.wait(x => x.requestId === requestId, ms)
  if (!f) return { denied: false, detail: '(no direct answer)' }
  const denied = f.type === 'error' && f.data.startsWith('demo:')
  return { denied, detail: `${f.type}: ${String(f.data).replace(/\s+/g, ' ').slice(0, 90)}` }
}

const pad = (s, n) => String(s).padEnd(n)
let bad = 0
const c = await connect()
const sessions = JSON.parse(c.frames.find(f => f.type === 'sessions').data)
const seeded = sessions.map(s => s.id)
const ready = c.frames.find(f => f.type === 'ready')
console.log(`ready: ${ready.data.replace(/demoNotice=\S+/, 'demoNotice=<b64>')}`)
console.log(`sessions (${sessions.length}): ${sessions.map(s => `${s.role}=${s.id} ${s.provider}/${s.model}`).join(', ')}`)
const A = seeded[0]

if (!scenarios) {
  console.log(`\nWebSocket frame types found in the code: ${frameTypes.size} (owner token, sessionId=${A}, payload "{}")`)
  console.log(pad('frame', 30) + pad('allow-list', 12) + pad('result', 9) + 'detail')
  const SKIP_REAL_TURN = new Set(['prompt', 'resume']) // a "{}" prompt on a seeded session would run a real turn: scenario-tested
  for (const t of [...frameTypes].sort()) {
    const expected = ALLOWED_FRAMES.has(t)
    if (SKIP_REAL_TURN.has(t)) { console.log(pad(t, 30) + pad('yes', 12) + pad('-', 9) + 'not swept (would run a real turn); see --scenarios'); continue }
    const r = await send(c, t, '{}', A)
    const ok = r.denied ? 'DENIED' : 'passed'
    if (!r.denied && !expected) bad++
    console.log(pad(t, 30) + pad(expected ? 'yes' : 'no', 12) + pad(ok, 9) + r.detail + (!r.denied && !expected ? '   <-- LEAK' : ''))
  }
  console.log(`\nHTTP routes found in the code: ${routes.length} (owner token)`)
  console.log(pad('route', 40) + pad('allow-list', 12) + pad('status', 8) + 'body')
  for (const [m, p, f] of routes) {
    const method = m === 'webSocket' ? 'GET' : m.toUpperCase()
    const key = `${method} ${p}`
    const expected = ALLOWED_HTTP.has(key)
    const concrete = p.replace('{threadId}', 'pair:a|b').replace('{id}', 'attack')
    if (/^\/(session|permission|question)/.test(p)) continue // opencode serve client calls (OpenCodeServeAdapter), not daemon routes
    const res = await fetch(base + concrete, { method, headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, body: method === 'GET' ? undefined : '{}' })
    const text = (await res.text()).replace(/\s+/g, ' ').slice(0, 70)
    const denied = res.status === 403 && text.startsWith('demo:')
    if (!denied && !expected) bad++
    console.log(pad(`${key}  (${f})`, 40) + pad(expected ? 'yes' : 'no', 12) + pad(res.status, 8) + text + (!denied && !expected ? '   <-- LEAK' : ''))
  }
  for (const p of ['/window/activate', '/admin', '/settings']) { // unknown / extra routes are refused too
    const res = await fetch(base + p, { method: 'POST', headers: { Authorization: `Bearer ${token}` } })
    console.log(pad(`POST ${p}  (not a route)`, 40) + pad('no', 12) + pad(res.status, 8) + (await res.text()).slice(0, 70))
  }
} else {
  const attack = async (label, type, data, sessionId) => {
    const r = await send(c, type, data, sessionId)
    if (!r.denied) bad++
    console.log(pad(label, 46) + pad(r.denied ? 'DENIED' : 'PASSED', 8) + r.detail)
  }
  console.log('\nnamed attacks with the OWNER token:')
  await attack('create a project', 'project_save', JSON.stringify({ name: 'evil', directory: '/work/evil' }))
  await attack('edit the demo project directory', 'project_save', JSON.stringify({ id: sessions[0].projectId, directory: '/data' }))
  await attack('spawn options for a third session', 'new', JSON.stringify({ provider: 'opencode', model: 'opencode-go/beam-free', role: 'qa' }))
  await attack('third session (prompt naming no session)', 'prompt', 'hello')
  await attack('third session (prompt naming unknown id)', 'prompt', 'hello', 'not-a-seeded-session')
  await attack('flip sandbox off', 'settings_set', JSON.stringify({ key: 'limits.sandbox.mode', value: 0 }))
  await attack('widen the model allowlist', 'settings_set', JSON.stringify({ key: 'limits.models.allowed', value: '*' }))
  await attack('raise the session cap', 'settings_set', JSON.stringify({ key: 'limits.concurrency.maxSessions', value: 0 }))
  await attack('paid model via /model', 'prompt', '/model anthropic/claude-opus-4', A)
  await attack('paid model via session_swap', 'session_swap', JSON.stringify({ sessionId: A, provider: 'claude', model: 'claude-opus-4' }))
  await attack('paid model via agent_swap', 'agent_swap', JSON.stringify({ agentId: 'x', provider: 'claude', model: 'claude-opus-4' }))
  await attack('install a harness', 'harness_install', 'claude')
  await attack('point a CLI location at a binary', 'cli_location_set', JSON.stringify({ id: 'opencode', path: '/bin/sh' }))
  await attack('set a secret', 'secret_set', JSON.stringify({ name: 'X', value: 'y' }))
  await attack('upload outside the sandbox', 'image_upload', JSON.stringify({ projectId: sessions[0].projectId, name: '../../data/x.png', data: 'AA==' }))
  await attack('mail from the owner to an agent', 'mail_send', JSON.stringify({ from: 'owner', to: A, body: 'hi' }))
  await attack('delete a demo session', 'session_delete', A)
  await attack('open the Assistant (a new session)', 'assistant', '')
  for (const [label, type] of [['write a project note', 'memory_save'], ['delete a project note', 'memory_delete'], ['create an epic', 'epic_save'], ['set a pipeline', 'pipeline_config']]) {
    await attack(`${label} in the demo project`, type, JSON.stringify({ projectId: sessions[0].projectId, name: 'evil', body: 'x', stages: ['a'] }))
  }
  await attack('switch the demo repository branch', 'project_git_checkout', JSON.stringify({ projectId: sessions[0].projectId, branch: 'evil', create: true }))
  // Per-project reads: another project, a blank or a missing projectId are refused by the lock.
  for (const t of PROJECT_READS) {
    await attack(`${t} naming another project`, t, JSON.stringify({ projectId: 'project-other', path: 'x', branch: 'main' }))
    await attack(`${t} naming no project`, t, t === 'project_git_status' || t === 'epic_list' ? '' : '{}')
  }
  // ... and naming the demo project they pass the lock, where the git service still refuses a path that leaves the repository.
  const P = sessions[0].projectId
  const contained = async (label, type, data) => {
    const r = await send(c, type, data)
    const ok = !r.denied && /refused/.test(r.detail)
    if (!ok) bad++
    console.log(pad(label, 46) + pad(ok ? 'REFUSED' : 'PASSED', 8) + r.detail)
  }
  await contained('git diff of ../../data/access-token', 'project_git_diff', JSON.stringify({ projectId: P, path: '../../data/access-token', staged: false }))
  await contained('git diff of /data/access-token', 'project_git_diff', JSON.stringify({ projectId: P, path: '/data/access-token', staged: false }))
  await contained('git worktree status of /data', 'project_git_worktree_status', JSON.stringify({ projectId: P, path: '/data' }))
  await contained('git branch changes of --output=/tmp/x', 'project_git_branch_changes', JSON.stringify({ projectId: P, branch: '--output=/tmp/x' }))
  for (const [t, d] of [['project_git_status', P], ['project_git_worktrees', JSON.stringify({ projectId: P })], ['epic_list', P], ['memory_list', P]]) {
    const r = await send(c, t, d)
    console.log(pad(`${t} naming the demo project`, 46) + pad(r.denied ? 'DENIED' : 'answered', 8) + r.detail)
    if (r.denied) bad++
  }
  const upd = await fetch(base + '/update/apply', { method: 'POST', headers: { Authorization: `Bearer ${token}` } })
  console.log(pad('apply an update (HTTP)', 46) + pad(upd.status === 403 ? 'DENIED' : 'PASSED', 8) + upd.status + ' ' + (await upd.text()).slice(0, 60))
  if (upd.status !== 403) bad++
}
c.ws.close()
console.log(bad === 0 ? '\nRESULT: PASS (only the allow-list got through)' : `\nRESULT: FAIL (${bad} got through)`)
process.exit(bad === 0 ? 0 : 1)
