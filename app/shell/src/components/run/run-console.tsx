// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// One run's console (xterm.js): `run_attach` answers with the kept scrollback (`run_bytes` replay: reset + write), then
// live `run_bytes` follow (a chunk already in the replay is skipped). Keystrokes go out as `run_input` when the run is
// interactive; a resize fits the terminal and sends `run_resize`.
import { useEffect, useRef } from "react";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";
import { runFeed } from "@infantry/client";
import { runsStore } from "./use-runs";

function cssColor(el: HTMLElement, prop: "color" | "backgroundColor"): string {
  return getComputedStyle(el)[prop] || (prop === "color" ? "#e5e5e5" : "#0a0a0a");
}

export function RunConsole({ runId, interactive }: { runId: string; interactive: boolean }) {
  const host = useRef<HTMLDivElement>(null);
  const interactiveRef = useRef(interactive);
  interactiveRef.current = interactive;

  useEffect(() => {
    const el = host.current;
    if (!el) return;
    const s = runsStore().getState();
    const term = new Terminal({
      convertEol: false,
      cursorBlink: true,
      fontFamily: "ui-monospace, SFMono-Regular, Consolas, monospace",
      fontSize: 12,
      scrollback: 10_000,
      theme: { background: cssColor(el, "backgroundColor"), foreground: cssColor(el, "color") },
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(el);

    const feed = runFeed();
    const offBytes = s.onBytes(runId, (b) => {
      const step = feed.accept(b);
      if (step.kind === "reset") {
        term.reset();
        term.write(step.bytes);
      } else if (step.kind === "write") term.write(step.bytes);
    });
    const offInput = term.onData((d) => {
      if (interactiveRef.current) s.input(runId, d);
    });

    let last = "";
    const refit = (): void => {
      try {
        fit.fit();
      } catch {
        return;
      }
      const key = `${term.cols}x${term.rows}`;
      if (key !== last && term.cols > 0 && term.rows > 0) {
        last = key;
        s.resize(runId, term.cols, term.rows);
      }
    };
    const ro = new ResizeObserver(() => refit());
    ro.observe(el);
    refit();
    s.attach(runId);

    return () => {
      ro.disconnect();
      offBytes();
      offInput.dispose();
      term.dispose();
    };
  }, [runId]);

  return <div ref={host} aria-label="Run console" className="h-full min-h-64 w-full overflow-hidden rounded-lg border bg-background p-1 text-foreground" />;
}
