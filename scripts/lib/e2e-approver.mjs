// SPDX-License-Identifier: Apache-2.0
// RR-QA: answers every tool-approval card (prompt_request kind approval) with allow_once, as the owner's app would on a click.
//   node e2e-approver.mjs <ws-url> <token-file> [seconds]   prints one line per answered card, never the token.
import { readFileSync } from 'node:fs';
const [url, tokFile, secs = '3600'] = process.argv.slice(2);
const tok = readFileSync(tokFile, 'utf8').trim();
const ws = new WebSocket(url, { headers: { Authorization: 'Bearer ' + tok } });
const seen = new Set();
const answer = (r) => {
  if (!r.promptId || seen.has(r.promptId) || !r.tool) return;
  seen.add(r.promptId);
  ws.send(JSON.stringify({ type: 'prompt_response', data: JSON.stringify({ promptId: r.promptId, decision: 'allow_once', answers: [] }), sessionId: null, sequence: null }));
  console.log(`approved ${r.tool} (${r.promptId.slice(0, 8)}) session ${String(r.sessionId).slice(0, 8)}`);
};
ws.onmessage = (m) => {
  let e; try { e = JSON.parse(m.data); } catch { return; }
  if (e.type !== 'prompt_request' && e.type !== 'prompt_requests' && e.type !== 'prompts') return;
  let d; try { d = JSON.parse(e.data); } catch { return; }
  for (const r of Array.isArray(d) ? d : [d]) answer(r);
};
ws.onerror = () => { console.error('approver: connection error'); };
setTimeout(() => process.exit(0), Number(secs) * 1000);
