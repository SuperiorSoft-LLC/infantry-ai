// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { initDaemon, needsPairing, perfScenario } from "./daemon/daemon";
import { ConnectScreen } from "./components/connect/connect-screen";
import { runPerfScenario } from "./daemon/perf-scenario";
import { PerfOverlay, perfOn } from "./components/layout/perf-overlay";
import { promptsStore } from "./components/session/use-prompts";
import { startUpdateCheck } from "./components/settings/desktop-install";
import "@infantry/ui/styles.css";

// The daemon store exists before the first render, so every component can read it synchronously.
void initDaemon().then(() => {
  // An unpaired phone (RUST-MOBILE-1): the connect screen only; pairing reloads the window into the shell.
  if (needsPairing) {
    ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(<ConnectScreen />);
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
  if (perfScenario) requestAnimationFrame(() => void runPerfScenario(perfScenario as string));
  // RUST-UPD-1: an installed desktop checks its feed once at start and posts a notice; it never installs by itself.
  else startUpdateCheck();
});
