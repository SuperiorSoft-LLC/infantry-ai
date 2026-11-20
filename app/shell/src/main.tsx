// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { initDaemon, daemonStore, daemonError, needsPairing, perfScenario } from "./daemon/daemon";
import { ConnectScreen } from "./components/connect/connect-screen";
import { runPerfScenario } from "./daemon/perf-scenario";
import { PerfOverlay, perfOn } from "./components/layout/perf-overlay";
import { promptsStore } from "./components/session/use-prompts";
import { listenDaemonNotices, startUpdateCheck } from "./components/settings/desktop-install";
import "@infantry/ui/styles.css";

/** Removes the start splash (index.html) once the UI has rendered; a daemon fault shows its message on it instead. */
function hideSplash() {
  const el = document.getElementById("splash");
  if (!el) return;
  el.style.opacity = "0";
  setTimeout(() => el.remove(), 220);
}
function splashMessage(text: string) {
  const m = document.getElementById("splash-msg");
  if (m) { m.textContent = text; m.hidden = false; }
}
/** BOOT-SPLASH-1: the daemon owns the boot steps; the splash only shows the current label and N of M, then hands over. */
function bootLabel(data: string): { label: string; ready: boolean } | null {
  try {
    const r = JSON.parse(data) as { label?: string; ready?: boolean };
    return typeof r.label === "string" ? { label: r.label, ready: r.ready === true } : null;
  } catch {
    return null;
  }
}
function followBoot(onReady: () => void): void {
  const store = daemonStore();
  let asked = false;
  const show = (text: string) => {
    const s = document.getElementById("splash-step");
    if (s) { s.textContent = text; s.hidden = false; }
  };
  store.getState().onFrame((env) => {
    if (env.type !== "boot_progress") return;
    const b = bootLabel(env.data);
    if (!b) return;
    show(b.label);
    if (b.ready) onReady();
  });
  const ask = () => {
    if (asked || store.getState().connection.state !== "open") return;
    asked = true;
    store.getState().sendFrame({ type: "boot_progress", data: "{}", requestId: "boot:1" });
  };
  store.subscribe(ask);
  ask();
}
// A daemon that never answers must not leave the loader up forever without saying why.
const splashTimer = setTimeout(() => splashMessage("Still connecting to the Infantry AI daemon..."), 8000);

// The daemon store exists before the first render, so every component can read it synchronously.
void initDaemon().then(() => {
  clearTimeout(splashTimer);
  if (daemonError) splashMessage(`The Infantry AI daemon did not start: ${daemonError}`);
  // An unpaired phone (RUST-MOBILE-1): the connect screen only; pairing reloads the window into the shell.
  if (needsPairing) {
    ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(<ConnectScreen />);
    hideSplash();
    return;
  }
  // The prompt store listens from the start: the connection burst carries the open prompt cards.
  promptsStore();
  ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
    <React.StrictMode>
      <App />
      {perfOn() && <PerfOverlay />}
    </React.StrictMode>,
  );
  listenDaemonNotices();
  // With a daemon fault the shell still opens (it shows its own banner); the message stays readable for a moment.
  // Otherwise the splash stays until the daemon reports boot ready (a short fallback keeps a silent daemon from holding it).
  if (daemonError) setTimeout(hideSplash, 2500);
  else {
    const fallback = setTimeout(hideSplash, 1500);
    followBoot(() => { clearTimeout(fallback); hideSplash(); });
  }
  if (perfScenario) requestAnimationFrame(() => void runPerfScenario(perfScenario as string));
  // RUST-UPD-1: an installed desktop checks its feed once at start and posts a notice; it never installs by itself.
  else startUpdateCheck();
}).catch((err) => {
  clearTimeout(splashTimer);
  splashMessage(`Could not start: ${err instanceof Error ? err.message : String(err)}`);
});
