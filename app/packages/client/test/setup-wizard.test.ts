// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Ports Kotlin SetupWizardTest. The key-row and "unprobed sign-in" cases have no Rust twin: the Rust daemon reads a
// harness's sign-in straight from its files (providers[].available), so there is no probe state to wait for.
import { describe, expect, it } from "vitest";
import { createHarnessInstallStore, installHarnesses, type Envelope, action, defaultPick, signInChoices, loginRow, signInPhase, signInRow, tokenSecretName, firstModel, firstStep, followInstalled, installed, needed, nodeNote, opensNow, outcomeFor, statusText, unprobed, type HarnessInstallReport, type HarnessInstallStatus, type SecretRow } from "../src";

const h = (id: string, isInstalled: boolean): HarnessInstallStatus => ({ id, label: id, npmPackage: `pkg-${id}`, state: isInstalled ? "installed" : "missing", path: isInstalled ? `/bin/${id}` : null });
const report = (hs: HarnessInstallStatus[], npm: string | null = "/usr/bin/npm"): HarnessInstallReport => ({ harnesses: hs, prefixDir: "/p", npmPath: npm, nodeInstallable: false, busy: false, log: [] });

const row = (id: string, o: Partial<SecretRow> = {}): SecretRow => ({ name: `harness.${id}.key`, label: id, set: false, tail: "", importable: false, overriddenByEnv: false, canLogin: true, signedIn: "false", lastProbe: 1, loginState: "", usesSecret: "", ...o });

describe("setup wizard", () => {
  it("opens when Node, an agent tool or a signed-in provider is missing, and stays closed only when all three are there", () => {
    expect(needed(null, [], [])).toBeNull();
    expect(needed(report([h("claude", false)]), null, [])).toBeNull();
    expect(needed(report([h("claude", false)]), [], null)).toBeNull();
    expect(needed(report([]), [], [])).toBeNull();
    expect(needed(report([h("claude", false), h("opencode", false)], null), [], [])).toBe(true);
    expect(needed(report([h("claude", true)]), [row("claude")], [{ id: "claude", available: false }])).toBe(true);
    expect(needed(report([h("claude", true)]), [row("claude", { signedIn: "true" })], [{ id: "claude", available: true }])).toBe(false);
    for (const id of ["codex", "someNewHarness"]) {
      expect(needed(report([h(id, true)]), [row(id, { signedIn: "true" })], [{ id }])).toBe(false);
      expect(needed(report([h(id, true)]), [row(id)], [{ id }])).toBe(true);
    }
  });

  it("reads the key row: a key, a confirmed or just-finished sign-in, or a shared secret all count", () => {
    const one = (r: Partial<SecretRow>) => needed(report([h("claude", true)]), [row("claude", r)], [{ id: "claude", available: false }]);
    expect(one({ set: true })).toBe(false);
    expect(one({ signedIn: "true" })).toBe(false);
    expect(one({ loginState: "done" })).toBe(false);
    expect(one({ usesSecret: "harness.shared.key" })).toBe(false);
    expect(one({})).toBe(true);
    // The row wins over a stale provider list; with no row the provider's `available` decides.
    expect(needed(report([h("claude", true)]), [row("claude")], [{ id: "claude", available: true }])).toBe(true);
    expect(needed(report([h("pi", true)]), [], [{ id: "pi", available: true }])).toBe(false);
    expect(needed(report([h("pi", true)]), [], [{ id: "pi", available: false }])).toBe(true);
    expect(needed(report([h("pi", true)]), [], [])).toBe(true);
  });

  it("waits while a sign-in has never been probed", () => {
    const fresh = row("claude", { signedIn: "unknown", lastProbe: 0 });
    const r = report([h("claude", true)]);
    const p = [{ id: "claude", available: false }];
    expect(unprobed(r, [fresh])).toHaveLength(1);
    expect(needed(r, [fresh], p)).toBeNull();
    expect(needed(r, [{ ...fresh, lastProbe: 5 }], p)).toBe(true);
    expect(needed(r, [{ ...fresh, canLogin: false }], p)).toBe(true);
    expect(needed(report([h("claude", false)]), [fresh], [])).toBe(true);
  });

  it("counts free models as 'can answer'", () => {
    const o = row("opencode", { canLogin: false, signedIn: "unknown" });
    expect(needed(report([h("opencode", true)]), [o], [{ id: "opencode", available: false, models: ["opencode/big-pickle", "opencode/grok-code-free"] }])).toBe(false);
    expect(needed(report([h("opencode", true)]), [o], [{ id: "opencode", available: false, defaultModel: "opencode/x-free" }])).toBe(false);
    const catalog = [{ provider: "opencode", models: ["opencode/deepseek-v4.1-flash-free"] }];
    expect(needed(report([h("opencode", true)]), [o], [{ id: "opencode", available: false }], catalog)).toBe(false);
    expect(firstModel("opencode", [o], [{ id: "opencode", available: false }], catalog)).toBe("opencode/deepseek-v4.1-flash-free");
  });

  it("opens again whenever it turns true, and 'Not now' holds only until the answer changes", () => {
    expect(opensNow(null, true)).toBe(true);
    expect(opensNow(false, true)).toBe(true);
    expect(opensNow(true, true)).toBe(false);
    expect(opensNow(null, false)).toBe(false);
    expect(opensNow(true, false)).toBe(false);
    expect(opensNow(false, null)).toBe(false);
  });

  it("makes the pick follow what is really installed", () => {
    const r = report([h("claude", false), h("pi", true), h("opencode", true)]);
    expect(followInstalled("claude", r)).toBe("pi");
    expect(followInstalled("opencode", r)).toBe("opencode");
    expect(followInstalled(null, r)).toBe("pi");
    expect(followInstalled("claude", report([h("claude", false)]))).toBe("claude");
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
    expect(firstModel("opencode", [], [{ ...p, available: false }])).toBe("opencode/grok-code-free");
    expect(firstModel("opencode", [], [{ ...p, available: true }])).toBe("anthropic/sonnet");
    expect(firstModel("opencode", [row("opencode", { signedIn: "true" })], [{ ...p, available: false }])).toBe("anthropic/sonnet");
    expect(firstModel("claude", [], [p])).toBeNull();
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

describe("harness install store", () => {
  it("re-reads the install report when an install is answered, so a fast failure shows instead of 'Installing' forever", () => {
    const sent: Envelope[] = [];
    const taps = new Set<(e: Envelope) => void>();
    const l = { sendFrame: (e: Envelope) => (sent.push(e), true), onFrame: (f: (e: Envelope) => void) => (taps.add(f), () => void taps.delete(f)) };
    const push = (e: Envelope) => taps.forEach((t) => t(e));
    const s = createHarnessInstallStore(l);
    s.getState().refresh();
    push({ type: "harness_install_status", data: JSON.stringify(report([h("opencode", false)], null)), requestId: sent[0].requestId });
    installHarnesses(s, [], { runtime: true });
    const before = sent.filter((e) => e.type === "harness_install_status").length;
    push({ type: "harness_install_result", data: JSON.stringify({ started: true }), requestId: sent[sent.length - 1].requestId });
    expect(sent.filter((e) => e.type === "harness_install_status").length).toBe(before + 1);
  });

  it("re-reads the install report when an install finishes anywhere (another window, the CLI)", () => {
    const sent: Envelope[] = [];
    const taps = new Set<(e: Envelope) => void>();
    const l = { sendFrame: (e: Envelope) => (sent.push(e), true), onFrame: (f: (e: Envelope) => void) => (taps.add(f), () => void taps.delete(f)) };
    const push = (e: Envelope) => taps.forEach((t) => t(e));
    const s = createHarnessInstallStore(l);
    s.getState().refresh();
    push({ type: "harness_install_status", data: JSON.stringify(report([h("claude", false)])), requestId: sent[0].requestId });
    const before = sent.length;
    // The daemon pushes `harness_install_result` with no requestId when an install it ran ends: not ours, still a reason to read again.
    push({ type: "harness_install_result", data: JSON.stringify({ done: true }) });
    expect(sent.length).toBe(before + 1);
    expect(sent[sent.length - 1].type).toBe("harness_install_status");
  });

  it("reads the browser sign-in a harness declares, and opens no matter which harness", () => {
    const row = (name: string, extra: Record<string, unknown> = {}) => ({ name, label: "X", set: false, tail: "", importable: false, overriddenByEnv: false, canLogin: true, ...extra });
    expect(tokenSecretName("claude")).toBe("harness.claude.token");
    expect(signInRow("claude", [row("harness.claude.key"), row("harness.claude.token")])?.name).toBe("harness.claude.token");
    expect(signInRow("opencode", [row("harness.claude.token")])).toBeNull();
    expect(signInRow("claude", [row("harness.claude.token", { canLogin: false })])).toBeNull();
    expect(signInPhase(null)).toBe("idle");
    expect(signInPhase(row("t", { loginState: "running" }))).toBe("running");
    expect(signInPhase(row("t", { loginState: "done" }))).toBe("done");
    expect(signInPhase(row("t", { loginState: "failed" }))).toBe("failed");
    // The token saved: the daemon lists the provider as available and the setup is no longer needed.
    expect(needed(report([h("claude", true)]), [], [{ id: "claude", available: false }])).toBe(true);
    expect(needed(report([h("claude", true)]), [], [{ id: "claude", available: true }])).toBe(false);
  });
  it("draws the two Kotlin choices: the subscription login (key row first, else the token row) and the API key", () => {
    const key = row("claude", { canLogin: true, help: "Anthropic key", authModes: ["api-key", "subscription-login"] });
    const token = { ...row("claude"), name: "harness.claude.token", canLogin: true };
    // A CLI login on the key row wins; without one the token row runs the same screen.
    expect(loginRow("claude", [key, token])?.name).toBe("harness.claude.key");
    expect(loginRow("claude", [{ ...key, canLogin: false }, token])?.name).toBe("harness.claude.token");
    expect(loginRow("pi", [key])).toBeNull();
    // Idle / running / failed keep the key card open; done (login or probe) says it is not needed.
    for (const state of ["", "running", "failed"]) {
      const c = signInChoices("claude", [{ ...key, loginState: state }]);
      expect([c.login?.name, c.key?.name, c.keyNeeded]).toEqual(["harness.claude.key", "harness.claude.key", true]);
    }
    expect(signInChoices("claude", [{ ...key, loginState: "done" }]).keyNeeded).toBe(false);
    expect(signInChoices("claude", [{ ...key, signedIn: "true" }]).keyNeeded).toBe(false);
    // A harness with no login gets the plain key row only.
    const plain = signInChoices("pi", [row("pi", { canLogin: false })]);
    expect([plain.login, plain.key?.name, plain.keyNeeded]).toEqual([null, "harness.pi.key", true]);
  });
});
