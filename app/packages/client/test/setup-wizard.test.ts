// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Ports Kotlin SetupWizardTest. The key-row and "unprobed sign-in" cases have no Rust twin: the Rust daemon reads a
// harness's sign-in straight from its files (providers[].available), so there is no probe state to wait for.
import { describe, expect, it } from "vitest";
import { action, defaultPick, firstModel, firstStep, installed, needed, nodeNote, outcomeFor, statusText, type HarnessInstallReport, type HarnessInstallStatus } from "../src";

const h = (id: string, isInstalled: boolean): HarnessInstallStatus => ({ id, label: id, npmPackage: `pkg-${id}`, state: isInstalled ? "installed" : "missing", path: isInstalled ? `/bin/${id}` : null });
const report = (hs: HarnessInstallStatus[], npm: string | null = "/usr/bin/npm"): HarnessInstallReport => ({ harnesses: hs, prefixDir: "/p", npmPath: npm, nodeInstallable: false, busy: false, log: [] });

describe("setup wizard", () => {
  it("opens only when nothing can answer, and never on a guess", () => {
    expect(needed(null, [])).toBeNull();
    expect(needed(report([h("claude", false)]), null)).toBeNull();
    expect(needed(report([]), [])).toBeNull();
    expect(needed(report([h("claude", false), h("opencode", false)]), [])).toBe(true);
    // Installed but the daemon says it cannot sign in: still nothing to talk to.
    expect(needed(report([h("claude", true)]), [{ id: "claude", available: false }])).toBe(true);
    expect(needed(report([h("claude", true)]), [{ id: "claude", available: true }])).toBe(false);
    // Installed but missing from the daemon's provider list: it cannot run.
    expect(needed(report([h("claude", true)]), [])).toBe(true);
    // Any harness, not only claude.
    for (const id of ["codex", "someNewHarness"]) {
      expect(needed(report([h(id, true)]), [{ id, available: true }])).toBe(false);
      expect(needed(report([h(id, true)]), [{ id, available: false }])).toBe(true);
    }
    // Free models count as "can answer": from the provider's list, its default, or the models catalog.
    expect(needed(report([h("opencode", true)]), [{ id: "opencode", available: false, models: ["opencode/big-pickle", "opencode/grok-code-free"] }])).toBe(false);
    expect(needed(report([h("opencode", true)]), [{ id: "opencode", available: false, defaultModel: "opencode/x-free" }])).toBe(false);
    const catalog = [{ provider: "opencode", models: ["opencode/deepseek-v4.1-flash-free"] }];
    expect(needed(report([h("opencode", true)]), [{ id: "opencode", available: false }], catalog)).toBe(false);
    expect(firstModel("opencode", [{ id: "opencode", available: false }], catalog)).toBe("opencode/deepseek-v4.1-flash-free");
  });

  it("starts at Node only while npm is missing and nothing is installed", () => {
    expect(firstStep(report([h("claude", false)], null))).toBe("node");
    expect(firstStep(report([h("claude", false)]))).toBe("harness");
    expect(firstStep(report([h("claude", true)], null))).toBe("sign-in");
    expect(defaultPick(report([h("claude", false), h("opencode", true)]))).toBe("opencode");
    expect(defaultPick(report([h("claude", false), h("opencode", false)]))).toBe("claude");
  });

  it("uses a free model for the first agent when the harness cannot answer otherwise", () => {
    const p = { id: "opencode", defaultModel: "anthropic/sonnet", models: ["anthropic/sonnet", "opencode/grok-code-free"] };
    expect(firstModel("opencode", [{ ...p, available: false }])).toBe("opencode/grok-code-free");
    expect(firstModel("opencode", [{ ...p, available: true }])).toBe("anthropic/sonnet");
    expect(firstModel("claude", [p])).toBeNull();
  });

  it("words an install outcome from its log line", () => {
    const log = ["[claude] npm install x", "node: installed", "claude: failed - npm exited 1: ETIMEDOUT"];
    expect(outcomeFor("claude", log)).toEqual({ ok: false, text: "Install failed: npm exited 1: ETIMEDOUT" });
    expect(outcomeFor("node", log)).toEqual({ ok: true, text: "Installed" });
    expect(outcomeFor("pi", ["pi: installed 2.1.0"])).toEqual({ ok: true, text: "Installed 2.1.0" });
    expect(outcomeFor("opencode", log)).toBeNull();
    expect(outcomeFor("codex", ["codex: skipped - already installed"])).toEqual({ ok: true, text: "Nothing to do: already installed" });
  });

  it("shows the Node note only without npm, and words each state", () => {
    expect(nodeNote(report([h("claude", false)]))).toBeNull();
    expect(nodeNote(report([h("claude", false)], null))).toContain("Node.js");
    expect(nodeNote({ ...report([], null), guidance: "go" })).toBe("go");
    expect(installed(h("claude", false))).toBe(false);
    expect(statusText({ ...h("claude", true), installedVersion: "2.1.0" })).toBe("Installed 2.1.0");
    expect(statusText(h("claude", false))).toBe("Not installed");
    expect(action(h("claude", false))).toBe("Install");
    expect(action(h("claude", true))).toBeNull();
  });
});
