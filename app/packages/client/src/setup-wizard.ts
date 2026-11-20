// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The decisions of the guided first-time setup (RUST PARITY of Kotlin SetupWizard + HarnessInstallSetting), kept out of
// the component so they are tested on their own. Every input is the DAEMON's own answer: its install report
// (harness_install_status) and its provider list (providers, where `available` = the harness can sign in / answer now).
// Nothing here names a harness: they are whatever the daemon's installer reports.
import type { HarnessInfo } from "./stores/launcher";

export interface HarnessInstallStatus {
  id: string;
  label: string;
  npmPackage: string;
  /** installed | missing | unreadable (found, version could not be read) | outdated | user-managed */
  state: string;
  installedVersion?: string | null;
  path?: string | null;
  managed?: boolean;
  wanted?: string | null;
  latest?: string | null;
  note?: string | null;
}

export interface HarnessInstallReport {
  harnesses: HarnessInstallStatus[];
  prefixDir: string;
  npmPath?: string | null;
  guidance?: string | null;
  /** npm is missing AND the daemon can install the Node.js runtime itself. */
  nodeInstallable: boolean;
  /** An install is running now. */
  busy: boolean;
  /** The install log: `<id>: <result> [- reason]` outcome lines and `[id] ...` progress lines. */
  log: string[];
}

export type SetupStep = "node" | "harness" | "sign-in" | "first-agent";
export const SETUP_STEPS: { id: SetupStep; title: string }[] = [
  { id: "node", title: "Get Node.js" },
  { id: "harness", title: "Pick your agent tool" },
  { id: "sign-in", title: "Sign in" },
  { id: "first-agent", title: "Say hello" },
];

/** The models of one provider in the `models` frame. */
export interface CatalogGroup {
  provider: string;
  models: string[];
}

/** The project the first agent runs in when the machine has none yet. */
export const STARTER_PROJECT = "My first project";
/** The first agent's task: short, so the reply arrives fast and costs next to nothing. */
export const HELLO_PROMPT = "Say hello in one short sentence and tell me you are ready to help.";

export const installed = (s: HarnessInstallStatus): boolean => !!s.path && s.state !== "missing";

/** The free models [id] offers: the provider's default and listed models, plus the catalog groups, whose id contains "free". */
export function freeModels(id: string, providers: HarnessInfo[], catalog: CatalogGroup[] = []): string[] {
  const p = providers.find((x) => x.id === id);
  const all = [...(p?.defaultModel ? [p.defaultModel] : []), ...(p?.models ?? []), ...catalog.filter((g) => g.provider === id).flatMap((g) => g.models)];
  return [...new Set(all.filter((m) => m.toLowerCase().includes("free")))];
}

/** True when [id] can answer without asking the user for anything more: the daemon says it is available, or it offers free models. */
export function credentialOk(id: string, providers: HarnessInfo[], catalog: CatalogGroup[] = []): boolean {
  const p = providers.find((x) => x.id === id);
  if (!p) return false;
  return p.available !== false || freeModels(id, providers, catalog).length > 0;
}

/** The harness ids that can answer now. */
export const talkable = (report: HarnessInstallReport, providers: HarnessInfo[], catalog: CatalogGroup[] = []): string[] =>
  report.harnesses.filter((h) => installed(h) && credentialOk(h.id, providers, catalog)).map((h) => h.id);

/** Whether the setup must open by itself: null while the daemon has not answered yet (never open on a guess). */
export function needed(report: HarnessInstallReport | null, providers: HarnessInfo[] | null, catalog: CatalogGroup[] = []): boolean | null {
  if (!report || !providers) return null;
  if (report.harnesses.length === 0) return null;
  return talkable(report, providers, catalog).length === 0;
}

/** Where the setup starts: Node only while npm is missing and nothing is installed yet; then the pick; then sign-in. */
export function firstStep(report: HarnessInstallReport): SetupStep {
  const none = !report.harnesses.some(installed);
  if (none && !report.npmPath) return "node";
  if (none) return "harness";
  return "sign-in";
}

/** The harness the pick screen starts on: one already installed, else the first the daemon lists. */
export const defaultPick = (report: HarnessInstallReport): string | null => (report.harnesses.find(installed) ?? report.harnesses[0])?.id ?? null;

/** The first agent's model: a free one when the harness cannot answer otherwise (so it answers at once), else the provider's default; null = the daemon picks. */
export function firstModel(id: string, providers: HarnessInfo[], catalog: CatalogGroup[] = []): string | null {
  const p = providers.find((x) => x.id === id);
  if (!p) return null;
  if (p.available === false) {
    const free = freeModels(id, providers, catalog)[0];
    if (free) return free;
  }
  return p.defaultModel ?? p.models?.[0] ?? null;
}

// ---- HarnessInstallSetting ----

const versionSuffix = (s: HarnessInstallStatus) => (s.installedVersion ? ` ${s.installedVersion}` : "");

export function statusText(s: HarnessInstallStatus): string {
  switch (s.state) {
    case "installed":
      return "Installed" + versionSuffix(s);
    case "outdated":
      return "Outdated" + versionSuffix(s) + (s.wanted ?? s.latest ? ` (want ${s.wanted ?? s.latest})` : "");
    case "missing":
      return "Not installed";
    case "user-managed":
      return s.installedVersion ? "Your install: works" + versionSuffix(s) : `Your install: ${s.note ?? "the check failed"}`;
    default:
      return "Found, but its version could not be read";
  }
}

/** The button label for [s], or null when there is nothing to do. */
export const action = (s: HarnessInstallStatus): string | null => (s.state === "missing" ? "Install" : s.state === "outdated" ? "Update" : null);

export interface InstallOutcome {
  ok: boolean;
  text: string;
}

/** What the last install said about [id], from its log (the `<id>: <result> [- reason]` lines the daemon writes), in words; null = no outcome yet. */
export function outcomeFor(id: string, log: string[]): InstallOutcome | null {
  const line = [...log].reverse().find((l) => l.startsWith(`${id}: `));
  if (!line) return null;
  const rest = line.slice(id.length + 2);
  const result = rest.split(" ")[0].split("-")[0].trim();
  const reasonAt = rest.indexOf(" - ");
  const reason = reasonAt >= 0 ? rest.slice(reasonAt + 3).trim() : "";
  switch (result) {
    case "failed":
      return { ok: false, text: "Install failed: " + (reason || "no reason given") };
    case "installed":
    case "updated": {
      const tail = rest.slice(result.length).split(" - ")[0].trimEnd();
      return { ok: true, text: (result === "installed" ? "Installed" : "Updated") + tail };
    }
    case "skipped":
      return { ok: true, text: "Nothing to do: " + (reason || "already installed") };
    default:
      return { ok: true, text: rest };
  }
}

/** Shown beside an Install button while the daemon has no npm: the daemon's own words, or a plain fallback. */
export const nodeNote = (report: HarnessInstallReport): string | null =>
  report.npmPath ? null : (report.guidance ?? "Node.js is not installed on the machine the daemon runs on, so this cannot be installed yet.");
