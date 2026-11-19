// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { initDaemon, daemonError, needsPairing, perfScenario } from "./daemon/daemon";
import { ConnectScreen } from "./components/connect/connect-screen";
import { runPerfScenario } from "./daemon/perf-scenario";
import { PerfOverlay, perfOn } from "./components/layout/perf-overlay";
import { promptsStore } from "./components/session/use-prompts";
import { startUpdateCheck } from "./components/settings/desktop-install";
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
  // With a daemon fault the shell still opens (it shows its own banner); the message stays readable for a moment.
  setTimeout(hideSplash, daemonError ? 2500 : 0);
  if (perfScenario) requestAnimationFrame(() => void runPerfScenario(perfScenario as string));
  // RUST-UPD-1: an installed desktop checks its feed once at start and posts a notice; it never installs by itself.
  else startUpdateCheck();
}).catch((err) => {
  clearTimeout(splashTimer);
  splashMessage(`Could not start: ${err instanceof Error ? err.message : String(err)}`);
});
