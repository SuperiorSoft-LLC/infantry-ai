// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Hop timings: a debug option (off by default) that stamps each hop a daemon frame takes to reach the screen, so a slow
// window can be measured end to end instead of guessed at. `t` is ms since the page started (performance.now), so two
// runs (a browser, a desktop window) line up by hop name. Enabled by the app (`debug.hopTimings`: ui.json
// `debugHopTimings`, env INFANTRY_UI_HOPS=1, or `?hops=1` in a browser); each line goes to the sink (console + a file).
let sink: ((line: string) => void) | null = null;

/** Turn the stamps on; [write] receives one JSON line per hop. */
export function enableHops(write: (line: string) => void): void {
  sink = write;
}

export function hopsOn(): boolean {
  return sink !== null;
}

const now = (): number => (typeof performance !== "undefined" ? Math.round(performance.now() * 10) / 10 : Date.now());

/** One hop: its name, the page-relative time, and what it carried (bytes, a frame type, a duration). */
export function hop(name: string, extra?: Record<string, unknown>): void {
  if (!sink) return;
  try {
    sink(JSON.stringify({ hop: name, t: now(), ...extra }));
  } catch {
    /* a debug aid never breaks the app */
  }
}

/** Run [f] and stamp how long it took (`ms`); [f] runs the same whether the stamps are on or not. */
export function timedHop<T>(name: string, f: () => T, extra?: Record<string, unknown>): T {
  if (!sink) return f();
  const a = performance.now();
  try {
    return f();
  } finally {
    hop(name, { ms: Math.round((performance.now() - a) * 10) / 10, ...extra });
  }
}
