// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import { initDaemon, perfScenario } from "./daemon/daemon";
import { runPerfScenario } from "./daemon/perf-scenario";
import { PerfOverlay, perfOn } from "./components/layout/perf-overlay";
import "@infantry/ui/styles.css";

// The daemon store exists before the first render, so every component can read it synchronously.
void initDaemon().then(() => {
  ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
    <React.StrictMode>
      <App />
      {perfOn() && <PerfOverlay />}
    </React.StrictMode>,
  );
  if (perfScenario) requestAnimationFrame(() => void runPerfScenario(perfScenario as string));
});
