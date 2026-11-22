// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The decisions of the guided first-time setup (RUST PARITY of Kotlin SetupWizard + HarnessInstallSetting), kept out of
// the component so they are tested on their own. Every input is the DAEMON's own answer: its install report
// (harness_install_status) and its provider list (providers, where `available` = the harness can sign in / answer now).
// Nothing here names a harness: they are whatever the daemon's installer reports.
import type { HarnessInfo } from "./stores/launcher";
import type { SecretRow } from "./stores/secrets";

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

/** The vault row of [id]'s key / sign-in (`harness.<id>.key`), when the harness declares one. */
export const keyRow = (id: string, secrets: SecretRow[]): SecretRow | undefined => secrets.find((r) => r.name === `harness.${id}.key`);

/** The in-app sign-in finished (the probe's `signedIn` may lag behind it). */
const LOGIN_DONE = "done";

/** The row holds a credential: a key is set, the sign-in is confirmed or just finished, or it inherits a shared secret. */
export const rowSignedIn = (r: SecretRow): boolean => r.set || r.signedIn === "true" || r.loginState === LOGIN_DONE || !!r.usesSecret?.trim();

/**
 * True when [id] can answer without asking the user for anything more. Kotlin reads the key row (set, signed in, login done, a
 * shared secret); a harness with no key row falls back to the daemon's `available`. Free models count either way.
 */
export function credentialOk(id: string, secrets: SecretRow[], providers: HarnessInfo[], catalog: CatalogGroup[] = []): boolean {
  if (freeModels(id, providers, catalog).length > 0) return true;
  const row = keyRow(id, secrets);
  if (row) return rowSignedIn(row);
  const p = providers.find((x) => x.id === id);
  return !!p && p.available !== false;
}

/** The harness ids that can answer now. */
export const talkable = (report: HarnessInstallReport, secrets: SecretRow[], providers: HarnessInfo[], catalog: CatalogGroup[] = []): string[] =>
  report.harnesses.filter((h) => installed(h) && credentialOk(h.id, secrets, providers, catalog)).map((h) => h.id);

/**
 * The key rows of installed harnesses whose sign-in the daemon has not probed yet (ONBOARD-CLAUDE-1): "unknown" and never probed.
 * Only a row with a sign-in to check counts; a harness that cannot sign in from here stays "unknown" for good, which is no reason to wait.
 */
export const unprobed = (report: HarnessInstallReport, secrets: SecretRow[]): SecretRow[] =>
  report.harnesses
    .filter(installed)
    .map((h) => keyRow(h.id, secrets))
    .filter((r): r is SecretRow => !!r && !!r.canLogin && !r.set && r.signedIn === "unknown" && !r.lastProbe);

/**
 * Whether the setup must open by itself: null while the daemon has not answered yet or a sign-in is still unprobed (never open on
 * a guess); false when Node, an agent tool and a signed-in provider are all there; true otherwise.
 */
export function needed(report: HarnessInstallReport | null, secrets: SecretRow[] | null, providers: HarnessInfo[] | null, catalog: CatalogGroup[] = []): boolean | null {
  if (!report || !secrets || !providers) return null;
  if (report.harnesses.length === 0) return null;
  if (talkable(report, secrets, providers, catalog).length > 0) return false;
  return unprobed(report, secrets).length > 0 ? null : true;
}

/**
 * Whether the setup opens by itself now: the moment [now] turns true after not being true (the first answer of an app start, or
 * a provider that was signed out later), never while it is already true, so closing it with "Not now" holds until the next change.
 */
export const opensNow = (before: boolean | null, now: boolean | null): boolean => now === true && before !== true;

/** Persistent "the first-time setup has been seen or was never needed" flag (webview storage lives in app data: it survives updates). */
export const SETUP_DONE_KEY = "infantry.setup.done";

type FlagStore = Pick<Storage, "getItem" | "setItem">;

export function setupDone(store: FlagStore | null): boolean {
  try {
    return store?.getItem(SETUP_DONE_KEY) === "1";
  } catch {
    return false;
  }
}

export function markSetupDone(store: FlagStore | null): void {
  try {
    store?.setItem(SETUP_DONE_KEY, "1");
  } catch {
    /* storage unavailable: the setup may show again next start */
  }
}

/**
 * Whether the setup opens by ITSELF: only on a true first install (flag never set). After that, a tool that is "not found right
 * now" (an update relaunch, a different PATH) never reopens it; the user opens it from Settings.
 */
export const autoOpens = (done: boolean, before: boolean | null, now: boolean | null): boolean => !done && opensNow(before, now);

/** The harness the pick follows: [pick] while it is installed (or nothing is), else the first that is. */
export function followInstalled(pick: string | null, report: HarnessInstallReport): string | null {
  const ids = report.harnesses.filter(installed).map((h) => h.id);
  return pick && ids.includes(pick) ? pick : (ids[0] ?? pick);
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
export function firstModel(id: string, secrets: SecretRow[], providers: HarnessInfo[], catalog: CatalogGroup[] = []): string | null {
  const p = providers.find((x) => x.id === id);
  if (!p) return null;
  const row = keyRow(id, secrets);
  if (row ? !rowSignedIn(row) : p.available === false) {
    const free = freeModels(id, providers, catalog)[0];
    if (free) return free;
  }
  return p.defaultModel ?? p.models?.[0] ?? null;
}

// ---- In-app sign-in of a harness (the daemon declares it as a built-in secret row `harness.<id>.token`) ----

/** The vault name of a harness's browser sign-in token. */
export const tokenSecretName = (id: string): string => `harness.${id}.token`;

/** The row of [id]'s browser sign-in, or null when its harness declares none (then only the other ways apply). */
export const signInRow = (id: string, rows: SecretRow[]): SecretRow | null => rows.find((r) => r.name === tokenSecretName(id) && r.canLogin) ?? null;

/**
 * The row that runs [id]'s subscription login: the key row when its CLI has a login (link + code), else the token row (the CLI's
 * token command, whose result is pasted). Both answer start / input / cancel and report loginState, so the screen is one flow.
 */
export const loginRow = (id: string, rows: SecretRow[]): SecretRow | null => {
  const key = rows.find((r) => r.name === `harness.${id}.key` && r.canLogin);
  return key ?? signInRow(id, rows);
};

/** The two ways to connect, as the Kotlin screen draws them (harness-neutral): the subscription login if declared, and the key. */
export function signInChoices(id: string, rows: SecretRow[]): { login: SecretRow | null; key: SecretRow | null; keyNeeded: boolean } {
  const login = loginRow(id, rows);
  const key = rows.find((r) => r.name === `harness.${id}.key`) ?? null;
  const done = signInPhase(login) === "done" || login?.signedIn === "true";
  return { login, key, keyNeeded: !done };
}

export type SignInPhase = "idle" | "running" | "done" | "failed";

/** Where the browser sign-in stands, from the daemon's row. */
export function signInPhase(row: SecretRow | null): SignInPhase {
  switch (row?.loginState) {
    case "running":
      return "running";
    case "done":
      return "done";
    case "failed":
      return "failed";
    default:
      return "idle";
  }
}

// ---- Install progress ----

/** After this long a running install gets the reassurance line (a cost trade-off, not a limit on the install). */
export const INSTALL_REASSURE_SECONDS = 60;

export interface InstallProgress {
  /** What is being installed (`node` or a harness id), from the newest `[id] ...` log line. */
  step: string | null;
  /** 0-100 when the log carries a download percent, else null (an indeterminate bar). */
  percent: number | null;
  /** The newest live line (the latest npm output, the download status), without its `[id] ` tag. */
  line: string;
}

/** What the running install is doing, read from the daemon's `[id] ...` progress lines. */
export function installProgress(log: string[]): InstallProgress {
  const tagged = log.filter((l) => /^\[[^\]]+\] /.test(l));
  const last = tagged[tagged.length - 1] ?? "";
  const m = /^\[([^\]]+)\] (.*)$/.exec(last);
  const live = [...tagged].reverse().find((l) => l.startsWith("[npm] ") || /\(\d+%\)\s*$/.test(l) || /^\[node\] /.test(l)) ?? last;
  const text = live.replace(/^\[[^\]]+\] /, "");
  const pct = /\((\d{1,3})%\)\s*$/.exec(text);
  const step = tagged.map((l) => /^\[([^\]]+)\]/.exec(l)?.[1] ?? "").filter((t) => t && t !== "npm").pop() ?? m?.[1] ?? null;
  return { step, percent: pct ? Math.min(100, Number(pct[1])) : null, line: text };
}

/** "1 min 05 s" for an install that has run [seconds]. */
export function elapsedText(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min ${String(s % 60).padStart(2, "0")} s`;
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
