// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The perf readout of stress mode (also on with `?perf=1`): frames per second from a rAF counter, JS heap when the
// engine reports it, store flushes and frames per flush, and how many transcript items the window holds.
// The same numbers are published on `window.__infantryPerf` so a Playwright run can read them.
import { useEffect, useState } from "react";
import { daemonStore, stressMode } from "@/daemon/daemon";

/** How often the readout refreshes (ms); fps is averaged over this window. */
const SAMPLE_MS = 1000;

export interface PerfSample {
  fps: number;
  minFps: number;
  heapMb: number | null;
  flushesPerSec: number;
  framesPerSec: number;
  lastBatch: number;
  items: number;
  sessions: number;
}

declare global {
  interface Window {
    __infantryPerf?: { last: PerfSample | null; samples: PerfSample[] };
  }
}

export const perfOn = () => stressMode || new URLSearchParams(location.search).get("perf") === "1";

export function PerfOverlay() {
  const [s, setS] = useState<PerfSample | null>(null);
  useEffect(() => {
    const perf: { last: PerfSample | null; samples: PerfSample[] } = { last: null, samples: [] };
    window.__infantryPerf = perf;
    let frames = 0, raf = 0, last = performance.now(), worst = Infinity, prevFrame = last;
    let prev = daemonStore().getState().stats;
    const tick = (t: number) => {
      frames++;
      const dt = t - prevFrame;
      if (dt > 0) worst = Math.min(worst, 1000 / dt);
      prevFrame = t;
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    const timer = setInterval(() => {
      const now = performance.now();
      const st = daemonStore().getState();
      const secs = (now - last) / 1000;
      const mem = (performance as Performance & { memory?: { usedJSHeapSize: number } }).memory;
      let items = 0;
      for (const v of Object.values(st.sessions)) items += v.items.length;
      const sample: PerfSample = {
        fps: Math.round(frames / secs),
        minFps: Number.isFinite(worst) ? Math.round(worst) : 0,
        heapMb: mem ? Math.round(mem.usedJSHeapSize / 1048576) : null,
        flushesPerSec: Math.round((st.stats.flushes - prev.flushes) / secs),
        framesPerSec: Math.round((st.stats.frames - prev.frames) / secs),
        lastBatch: st.stats.lastBatch,
        items,
        sessions: Object.keys(st.sessions).length,
      };
      perf.last = sample;
      perf.samples.push(sample);
      setS(sample);
      prev = st.stats;
      frames = 0;
      worst = Infinity;
      last = now;
    }, SAMPLE_MS);
    return () => {
      cancelAnimationFrame(raf);
      clearInterval(timer);
    };
  }, []);
  if (!s) return null;
  return (
    <div
      data-testid="perf"
      className="pointer-events-none fixed top-12 right-3 z-50 rounded-lg border bg-card/90 px-3 py-2 font-mono text-[11px] leading-5 shadow-md backdrop-blur"
    >
      <div className={s.fps >= 55 ? "text-ok" : s.fps >= 30 ? "text-warn" : "text-danger"}>
        {s.fps} fps <span className="text-muted-foreground">(worst frame {s.minFps})</span>
      </div>
      <div>heap {s.heapMb != null ? `${s.heapMb} MB` : "n/a"}</div>
      <div>
        {s.framesPerSec} frames/s in {s.flushesPerSec} flushes (last {s.lastBatch})
      </div>
      <div>
        {s.items.toLocaleString()} items · {s.sessions} sessions
      </div>
    </div>
  );
}
