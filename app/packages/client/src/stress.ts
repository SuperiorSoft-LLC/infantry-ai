// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Stress mode: fake sessions with a long history and a fast live stream, fed through the SAME ingest path the daemon's
// frames take (sequencing, per-frame batching, the transcript reducer). Nothing here talks to a daemon.
import { Frame, type Envelope, type SessionRecord } from "./wire";
import type { DaemonStore } from "./store";

export interface StressOptions {
  /** Fake sessions to stream into. */
  sessions: number;
  /** Transcript items of history each session starts with. */
  messages: number;
  /** Live frames per second, per session. */
  framesPerSecond: number;
}

export const DEFAULT_STRESS: StressOptions = { sessions: 6, messages: 10_000, framesPerSecond: 100 };

const ROLES = ["architect", "backend", "frontend", "fullstack", "pm", "qa", "automation"];
const WORDS = ("the daemon streams every frame through one socket while the shell batches deltas per animation frame and "
  + "keeps finished messages memoised so only the live row renders again").split(" ");

class FakeSession {
  seq = 0;
  n = 0;
  constructor(readonly id: string, readonly record: SessionRecord) {}
  frame(type: string, data: string): Envelope {
    this.seq += 1;
    return { type, data, sessionId: this.id, sequence: this.seq };
  }
  words(k: number): string {
    let s = "";
    for (let i = 0; i < k; i++) s += `${WORDS[(this.n++ * 7 + i) % WORDS.length]} `;
    return s;
  }
  /** One whole exchange: a turn, thinking, a tool, an edit every few turns, a reply, the finish. ~6 items. */
  exchange(i: number): Envelope[] {
    const out = [
      this.frame(Frame.TURN, `Stress turn ${i}: report the state of step ${i}.`),
      this.frame(Frame.REASONING, this.words(18)),
      this.frame(Frame.STATUS, JSON.stringify({ state: "BUSY", tool: "Read", activity: `reading src/file-${i % 97}.ts` })),
    ];
    if (i % 4 === 0) {
      out.push(this.frame(Frame.STATUS, JSON.stringify({
        state: "BUSY",
        write: { path: `app/shell/src/stress/file-${i % 97}.ts`, edits: [{ oldText: `const step = ${i - 1};`, newText: `const step = ${i};\nexport default step;` }] },
      })));
    }
    out.push(this.frame(Frame.TEXT_DELTA, this.words(40)));
    out.push(this.frame(Frame.FINISHED, JSON.stringify({ exitCode: 0, outcome: "SUCCEEDED" })));
    return out;
  }
}

/** Seed fake sessions with history, then stream into all of them until the returned stop() is called. */
export function startStress(store: DaemonStore, opts: Partial<StressOptions> = {}): () => void {
  const o = { ...DEFAULT_STRESS, ...opts };
  const now = Date.now();
  const fakes: FakeSession[] = [];
  for (let k = 0; k < o.sessions; k++) {
    const id = `stress-${k + 1}`;
    const record: SessionRecord = {
      id, provider: "stub", workingDir: "stress", spawnedAt: now - k * 60_000, lastHeartbeat: now, state: "BUSY", turnActive: true,
      role: ROLES[k % ROLES.length], model: "stress", runCount: 0, ticketId: null,
      progress: k % 2 === 0 ? { mode: "steps", state: "running", percent: null, label: "streaming", startedAt: now, updatedAt: now } : null,
    };
    fakes.push(new FakeSession(id, record));
  }
  // History: exchanges until the reducer would hold ~`messages` items (about 5.25 items per exchange).
  for (const f of fakes) {
    store.getState().ingest(f.frame(Frame.SESSION, JSON.stringify(f.record)));
    const exchanges = Math.ceil(o.messages / 5.25);
    for (let i = 0; i < exchanges; i++) for (const env of f.exchange(i)) store.getState().ingest(env);
    store.getState().ingest(f.frame(Frame.TURN, "Keep streaming."));
  }
  // Live: each tick sends every session a delta; every 60th frame closes the reply and opens a new turn.
  const tickMs = 10;
  const perTick = Math.max(1, Math.round((o.framesPerSecond * tickMs) / 1000));
  let tick = 0;
  const timer = setInterval(() => {
    tick += 1;
    const ingest = store.getState().ingest;
    for (const f of fakes) {
      for (let j = 0; j < perTick; j++) {
        const n = tick * perTick + j;
        if (n % 60 === 59) {
          ingest(f.frame(Frame.FINISHED, JSON.stringify({ exitCode: 0, outcome: "SUCCEEDED" })));
          ingest(f.frame(Frame.TURN, `Live turn ${n}.`));
        } else if (n % 60 === 1) {
          ingest(f.frame(Frame.REASONING, f.words(10)));
        } else if (n % 60 === 2) {
          ingest(f.frame(Frame.STATUS, JSON.stringify({ state: "BUSY", tool: "Bash", activity: `run step ${n}` })));
        } else {
          ingest(f.frame(Frame.TEXT_DELTA, f.words(3)));
        }
      }
    }
  }, tickMs);
  return () => clearInterval(timer);
}
