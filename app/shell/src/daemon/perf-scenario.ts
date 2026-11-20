// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// RUST-PERF-1: the perf harness's scenario, run in-page (no synthetic desktop input). scripts/perf/compare.ps1 starts the
// app with INFANTRY_PERF_SCENARIO="agents=5;idle=20;cwd=<throwaway folder>;model=haiku" and INFANTRY_PERF_OUT=<file>.
// The page spawns the agents through the same store actions the UI uses, waits for their first turn, holds an idle
// window, then has every agent stream one reply while it counts animation frames. Each step is one JSON line in the
// out file ({event, t}); the harness samples memory and CPU outside the process and cuts its samples at these marks.
import { host } from "@/host";
import { daemonStore } from "./daemon";

const FIRST = "Reply with the word ok and nothing else.";
const STREAM = "Write about 300 words about rivers, in plain prose. Do not use any tools.";

function report(event: string, extra: Record<string, unknown> = {}) {
  void host.call("perf_report", { line: JSON.stringify({ event, t: Date.now(), ...extra }) }).catch(() => {});
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Turns finished per session (the `finished` transcript items). */
function finished(): Record<string, number> {
  const out: Record<string, number> = {};
  for (const [id, v] of Object.entries(daemonStore().getState().sessions)) out[id] = v.items.filter((i) => i.kind === "finished").length;
  return out;
}

async function until(ok: () => boolean, timeoutMs: number): Promise<boolean> {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    if (ok()) return true;
    await sleep(250);
  }
  return ok();
}

export async function runPerfScenario(spec: string) {
  const kv = Object.fromEntries(spec.split(";").map((p) => p.split("=")).filter((p) => p.length === 2).map(([k, v]) => [k.trim(), v.trim()]));
  const n = Number(kv.agents ?? 1);
  const idleSec = Number(kv.idle ?? 20);
  const model = kv.model ?? "haiku";
  report("ui-ready", { timeOrigin: Math.round(performance.timeOrigin) });
  if (n <= 0) return;
  await until(() => daemonStore().getState().connection.state === "open", 10_000);
  // A quiet window first, so the harness samples the app with no agents.
  await sleep(Number(kv.delay ?? 0) * 1000);
  report("spawn-start");
  const store = daemonStore().getState();
  for (let k = 0; k < n; k++) store.spawnWith({ name: `perf-${k + 1}`, model, cwd: kv.cwd, prompt: FIRST });
  const up = await until(() => {
    const f = finished();
    return Object.keys(f).length >= n && Object.values(f).every((c) => c >= 1);
  }, 240_000);
  report("agents-up", { ok: up, sessions: Object.keys(finished()).length });
  await sleep(2000);
  report("idle-start");
  await sleep(idleSec * 1000);
  report("idle-end");

  // Streaming: every agent writes one reply at once while the frame counter runs.
  const before = finished();
  let frames = 0, worst = Infinity, last = performance.now(), raf = 0;
  const gaps: number[] = [];
  const tick = (t: number) => {
    frames++;
    const dt = t - last;
    if (dt > 0) { worst = Math.min(worst, 1000 / dt); gaps.push(dt); }
    last = t;
    raf = requestAnimationFrame(tick);
  };
  const t0 = performance.now();
  raf = requestAnimationFrame(tick);
  report("stream-start");
  const s = daemonStore().getState();
  for (const id of Object.keys(before)) s.send(id, STREAM);
  const done = await until(() => {
    const f = finished();
    return Object.keys(before).every((id) => (f[id] ?? 0) > before[id]);
  }, 240_000);
  cancelAnimationFrame(raf);
  const secs = (performance.now() - t0) / 1000;
  gaps.sort((a, b) => a - b);
  const p99 = gaps.length ? gaps[Math.min(gaps.length - 1, Math.floor(gaps.length * 0.99))] : 0;
  report("stream-end", {
    ok: done,
    fps: Math.round((frames / secs) * 10) / 10,
    worstFps: Number.isFinite(worst) ? Math.round(worst) : 0,
    p99FrameMs: Math.round(p99 * 10) / 10,
    longFrames: gaps.filter((g) => g > 50).length,
    seconds: Math.round(secs),
  });
  report("scenario-done");
}
