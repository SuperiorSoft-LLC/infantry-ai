// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The guided first-time setup (RUST PARITY of Kotlin ShellSetupWizard). It comes back until Node.js, an agent tool AND a signed-in
// provider are all there (`needed`): on every app start, and again whenever that turns true after being false. "Not now" closes it
// until then; Settings > Daemon opens it at any time. One choice per screen:
//  1. Node.js (only while npm is missing and nothing is installed): the daemon installs it into the app's own folder.
//  2. Pick an agent tool the daemon's installer offers and install it (plain failure + retry).
//  3. Sign in (skipped when the tool can already answer: a login, a key or free models).
//  4. Start a first agent in a starter project and show its reply.
// Every decision is `@infantry/client` setup-wizard.ts (tested); this file draws the daemon's answers.
import { useCallback, useEffect, useRef, useState } from "react";
import { useStore } from "zustand";
import { SubscriptionSignIn } from "@/components/setup/subscription-sign-in";
import {
  createHarnessInstallStore,
  createQueryStore,
  defaultPick,
  credentialOk,
  elapsedText,
  firstModel,
  INSTALL_REASSURE_SECONDS,
  installProgress,
  followInstalled,
  signInChoices,
  autoOpens,
  markSetupDone,
  setupDone,
  firstStep,
  HELLO_PROMPT,
  installHarnesses,
  needed,
  nodeNote,
  outcomeFor,
  SETUP_STEPS,
  STARTER_PROJECT,
  statusText,
  installed,
  type CatalogGroup,
  type HarnessInfo,
  type HarnessInstallReport,
  type SecretRow,
  type SetupStep,
} from "@infantry/client";
import { AppDialog, Button, cn, Input, ProgressLine } from "@infantry/ui";
import { daemonStore, useDaemon } from "@/daemon/daemon";
import { secretsStore } from "@/daemon/secrets-store";
import { useUi } from "@/state/ui-state";
import { host } from "@/host";

let installStore: ReturnType<typeof createHarnessInstallStore> | null = null;
let providersStore: ReturnType<typeof createQueryStore<HarnessInfo[]>> | null = null;
let catalogStore: ReturnType<typeof createQueryStore<CatalogGroup[]>> | null = null;

function stores() {
  const link = daemonStore().getState();
  installStore ??= createHarnessInstallStore(link);
  // `refresh` makes the daemon rebuild its harness list now (it serves a cached one otherwise): sign-in and installs show at once.
  providersStore ??= createQueryStore<HarnessInfo[]>(link, {
    prefix: "setup-p",
    request: "providers",
    body: () => JSON.stringify({ refresh: true }),
    answers: ["providers"],
    empty: [],
    parse: (raw) => (Array.isArray(raw) ? (raw as HarnessInfo[]) : null),
  });
  catalogStore ??= createQueryStore<CatalogGroup[]>(link, {
    prefix: "setup-m",
    request: "models",
    answers: ["models"],
    empty: [],
    parse: (raw) => (Array.isArray(raw) ? (raw as CatalogGroup[]) : null),
  });
  return { install: installStore, providers: providersStore, catalog: catalogStore };
}

/** The daemon's three answers the setup reads, asked once the link is open; `recheck` asks them again. */
export function useSetupData() {
  const open = useDaemon((s) => s.connection.state) === "open";
  const s = stores();
  const secrets = secretsStore();
  const report = useStore(s.install, (x) => x.data);
  const secretsLoaded = useStore(secrets, (x) => x.loaded);
  const rows = useStore(secrets, (x) => x.builtIns);
  const providersLoaded = useStore(s.providers, (x) => x.loaded);
  const providers = useStore(s.providers, (x) => x.data);
  const catalog = useStore(s.catalog, (x) => x.data);
  const recheck = useCallback(() => {
    s.install.getState().refresh();
    s.providers.getState().refresh();
    s.catalog.getState().refresh();
    secrets.getState().refresh();
  }, [s.install, s.providers, s.catalog, secrets]);
  useEffect(() => {
    if (open) recheck();
  }, [open, recheck]);
  return { report, secrets: secretsLoaded ? rows : null, providers: providersLoaded ? providers : null, catalog, recheck };
}

/**
 * Opens the setup by itself whenever it becomes needed: the first answer of an app start, and again if a tool or a sign-in goes
 * missing later. "Not now" keeps it closed until the answer changes or the app restarts. Never on a guess (null = not answered yet).
 */
export function useAutoSetup(): void {
  const ui = useUi();
  const { report, secrets, providers, catalog } = useSetupData();
  const before = useRef<boolean | null>(null);
  const want = needed(report, secrets, providers, catalog);
  useEffect(() => {
    const store = typeof localStorage === "undefined" ? null : localStorage;
    // Everything is fine now, or the setup has been shown once: it never opens by itself again (Settings > Daemon still opens it).
    if (want === false) markSetupDone(store);
    if (autoOpens(setupDone(store), before.current, want) && !ui.dialog) {
      markSetupDone(store);
      ui.set("dialog", "setup");
    }
    // Another dialog was open: the next render tries again, so the setup is not lost behind it.
    if (want !== true || !ui.dialog || ui.dialog === "setup") before.current = want;
  }, [want, ui]);
}

function Choice({ selected, disabled, onClick, title, hint }: { selected: boolean; disabled: boolean; onClick: () => void; title: string; hint: string }) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      disabled={disabled}
      onClick={onClick}
      className={cn("flex w-full items-center gap-3 rounded-lg border bg-card p-3 text-left disabled:opacity-60", selected && "border-primary")}
    >
      <span className={cn("size-3.5 shrink-0 rounded-full border", selected ? "border-primary bg-primary" : "border-muted-foreground")} />
      <span className="min-w-0">
        <span className="block text-[13px] font-semibold">{title}</span>
        <span className="block text-xs text-muted-foreground">{hint}</span>
      </span>
    </button>
  );
}

/** A running install: a bar (a percent when the log has one), the time it has run, the live line, and a reassurance after a minute. */
function InstallProgressView({ log }: { log: string[] }) {
  const p = installProgress(log);
  const [seconds, setSeconds] = useState(0);
  useEffect(() => {
    const t = setInterval(() => setSeconds((s) => s + 1), 1000);
    return () => clearInterval(t);
  }, []);
  return (
    <div className="space-y-1.5" data-testid="install-progress">
      <ProgressLine value={p.percent} />
      <p className="text-xs text-muted-foreground">
        Installing{p.step ? ` ${p.step}` : ""}... {elapsedText(seconds)}
      </p>
      {p.line ? <p className="truncate font-mono text-[11px] text-muted-foreground">{p.line}</p> : null}
      {seconds >= INSTALL_REASSURE_SECONDS ? <p className="text-xs text-muted-foreground">Still installing; first installs can take a few minutes.</p> : null}
    </div>
  );
}

function Log({ lines }: { lines: string[] }) {
  const shown = lines.filter((l) => l.startsWith("[")).slice(-4);
  if (shown.length === 0) return null;
  return <pre className="max-h-24 overflow-auto rounded-md border bg-muted/40 p-2 font-mono text-[11px] text-muted-foreground">{shown.join("\n")}</pre>;
}

function NodeStep({ report, recheck }: { report: HarnessInstallReport; recheck: () => void }) {
  const started = useRef(false);
  const outcome = outcomeFor("node", report.log);
  // The owner's call (Kotlin): install it automatically when it is missing; started once on this screen, "Try again" after a failure.
  useEffect(() => {
    if (report.nodeInstallable && !report.busy && !started.current) {
      started.current = true;
      installHarnesses(stores().install, [], { runtime: true });
    }
  }, [report.nodeInstallable, report.busy]);
  return (
    <div className="space-y-2.5">
      <p className="text-[13px]">The agent tools run on Node.js, and this computer does not have it yet.</p>
      {report.nodeInstallable ? (
        <>
          <p className="text-xs text-muted-foreground">Installing it for you now: about 40 MB, into Infantry AI's own folder. No admin prompt, nothing else on your PC changes.</p>
          {report.busy ? <InstallProgressView log={report.log} /> : <Log lines={report.log} />}
          {outcome && !outcome.ok && !report.busy && (
            <>
              <p role="alert" className="text-xs text-danger">{outcome.text}</p>
              <Button size="sm" onClick={() => installHarnesses(stores().install, [], { runtime: true })}>
                Try again
              </Button>
            </>
          )}
        </>
      ) : (
        <>
          <p className="text-xs text-muted-foreground">{nodeNote(report)}</p>
          <div className="flex gap-2">
            <Button size="sm" variant="outline" onClick={() => void host.openExternal("https://nodejs.org/en/download")}>
              Open nodejs.org
            </Button>
            <Button size="sm" onClick={recheck}>
              Check again
            </Button>
          </div>
        </>
      )}
    </div>
  );
}

function HarnessStep({ report, pick, onPick }: { report: HarnessInstallReport; pick: string | null; onPick: (id: string) => void }) {
  const outcome = pick ? outcomeFor(pick, report.log) : null;
  return (
    <div className="space-y-2.5">
      <p className="text-[13px]">Pick the agent tool you want to use. You can add the others later.</p>
      <div role="radiogroup" className="space-y-2">
        {report.harnesses.map((h) => (
          <Choice key={h.id} selected={h.id === pick} disabled={report.busy} onClick={() => onPick(h.id)} title={h.label || h.id} hint={statusText(h)} />
        ))}
      </div>
      {report.busy ? <InstallProgressView log={report.log} /> : <Log lines={report.log} />}
      {!report.busy && outcome && !outcome.ok && (
        <p role="alert" className="text-xs text-danger">
          {outcome.text} Press Install to try again.
        </p>
      )}
    </div>
  );
}

/** A bordered card for one way to connect (Kotlin `ChoiceCard`). */
function ChoiceCard({ title, subtitle, children }: { title: string; subtitle?: string; children: React.ReactNode }) {
  return (
    <div className="space-y-2 rounded-lg border bg-card p-3">
      <p className="text-[13px] font-semibold">{title}</p>
      {subtitle ? <p className="text-xs text-muted-foreground">{subtitle}</p> : null}
      {children}
    </div>
  );
}

/** The sign-in step, as the Kotlin screen: the harness's name, then the ways it declares (a subscription login, an API key). */
function SignInStep({ id, label, rows, recheck }: { id: string; label: string; rows: SecretRow[]; recheck: () => void }) {
  const [key, setKey] = useState("");
  const [error, setError] = useState<string | null>(null);
  const { login, key: keyRowOf, keyNeeded } = signInChoices(id, rows);
  const showKey = !!keyRowOf && keyRowOf.authModes?.includes("api-key") !== false;
  return (
    <div className="space-y-2.5">
      <p className="text-[13px] font-semibold">{label}</p>
      <p className="text-xs text-muted-foreground">{login && showKey ? `Choose one way to connect ${label}.` : `Connect ${label} so your agent can answer.`}</p>
      {login && (
        <ChoiceCard title={`Log in with your ${label} subscription`} subtitle={`A login window opens so you can log in to your ${label} account.`}>
          <SubscriptionSignIn id={id} label={label} onSignedIn={recheck} />
        </ChoiceCard>
      )}
      {showKey && keyRowOf && (
        <ChoiceCard title={login ? `Use an API key` : `${label} API key`} subtitle={keyRowOf.help}>
          {keyNeeded ? (
            <div className="flex gap-2">
              <Input aria-label="API key" type="password" autoComplete="off" value={key} onChange={(e) => setKey(e.target.value)} placeholder="API key" />
              <Button
                size="sm"
                disabled={!key.trim()}
                onClick={() => secretsStore().getState().setField(keyRowOf.name, "value", key.trim()).then(() => (setKey(""), recheck()), (why: unknown) => setError(String(why)))}
              >
                Save key
              </Button>
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">Not needed: you are logged in with your subscription.</p>
          )}
        </ChoiceCard>
      )}
      {error ? (
        <p role="alert" className="text-xs text-danger">
          {error}
        </p>
      ) : null}
    </div>
  );
}

/** The project the first agent runs in: the first there is, else a starter the daemon makes (zero typing). */
function FirstAgentStep({ id, secrets, providers, catalog }: { id: string; secrets: SecretRow[]; providers: HarnessInfo[]; catalog: CatalogGroup[] }) {
  const projects = useDaemon((s) => s.projects);
  const sessions = useDaemon((s) => s.sessions);
  const before = useRef(new Set(Object.keys(daemonStore().getState().sessions)));
  const asked = useRef<"none" | "project" | "agent">("none");
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    const store = daemonStore().getState();
    if (asked.current === "agent") return;
    const project = projects.find((p) => p.directory) ?? projects[0];
    if (!project) {
      if (asked.current === "none") {
        asked.current = "project";
        store.sendFrame({ type: "project_save", data: JSON.stringify({ name: STARTER_PROJECT, starter: true }) });
      }
      return;
    }
    asked.current = "agent";
    const opts: Record<string, unknown> = { provider: id, name: "First agent", prompt: HELLO_PROMPT };
    const model = firstModel(id, secrets, providers, catalog);
    if (model) opts.model = model;
    if (project.directory) opts.cwd = project.directory;
    if (!store.spawnWith(opts)) setError("the daemon link is down");
  }, [projects, id, secrets, providers, catalog]);
  const mine = Object.values(sessions).find((v) => !before.current.has(v.record.id) && v.record.provider === id);
  const reply = mine?.items.filter((i) => i.kind === "text").map((i) => (i as { text: string }).text).join("").trim() ?? "";
  const failed = error ?? (mine?.record.state === "FAILED" ? (mine.record.lastError ?? "the agent failed") : mine?.items.find((i) => i.kind === "note" && i.tone === "danger") && "the agent reported an error");
  return (
    <div className="space-y-2.5">
      <p className="text-xs text-muted-foreground">Your first agent is starting. It was asked: "{HELLO_PROMPT}"</p>
      {reply ? (
        <>
          <p data-testid="setup-reply" className="text-sm">{reply}</p>
          <p className="text-xs text-muted-foreground">It works. Press Done and start working; the guided setup stays in Settings.</p>
        </>
      ) : failed ? (
        <p role="alert" className="text-xs text-danger">The agent could not answer: {failed}</p>
      ) : (
        <p className="text-xs text-muted-foreground">Waiting for the reply...</p>
      )}
    </div>
  );
}

export function SetupWizardDialog({ onClose }: { onClose: () => void }) {
  const { report, secrets, providers, catalog, recheck } = useSetupData();
  if (!report) return <AppDialog open onOpenChange={(o) => !o && onClose()} title="Setup" description="Asking the daemon what is installed..." footer={null}>{null}</AppDialog>;
  return <Wizard report={report} secrets={secrets ?? []} providers={providers ?? []} catalog={catalog} recheck={recheck} onClose={onClose} />;
}

function Wizard({ report, secrets, providers, catalog, recheck, onClose }: { report: HarnessInstallReport; secrets: SecretRow[]; providers: HarnessInfo[]; catalog: CatalogGroup[]; recheck: () => void; onClose: () => void }) {
  const [step, setStep] = useState<SetupStep>(() => firstStep(report));
  const [pick, setPick] = useState<string | null>(() => defaultPick(report));
  const picked = report.harnesses.find((h) => h.id === pick);
  const pickedInstalled = !!picked && installed(picked);
  const sawMissing = useRef(false);
  const installedKey = report.harnesses.filter(installed).map((h) => h.id).join(",");

  // While an install runs the daemon's log is the progress: read it again every 1.5 s.
  useEffect(() => {
    if (!report.busy) return;
    const t = setInterval(() => stores().install.getState().refresh(), 1500);
    return () => clearInterval(t);
  }, [report.busy]);
  // An install finished, here, in another window or in the CLI (the report is re-read on the daemon's push): what is installed
  // changed, so the sign-in rows, the harness list and the models are read again.
  const seenInstalled = useRef(installedKey);
  useEffect(() => {
    if (seenInstalled.current === installedKey) return;
    seenInstalled.current = installedKey;
    stores().providers.getState().refresh();
    stores().catalog.getState().refresh();
    secretsStore().getState().refresh();
  }, [installedKey]);
  // The pick follows what is really installed.
  useEffect(() => {
    setPick((p) => followInstalled(p, report));
  }, [report]);
  // Step changes follow the daemon's answers, never a guess: Node found -> the pick; installed -> sign-in; can answer -> first agent.
  useEffect(() => {
    if (step === "node" && report.npmPath && !report.busy) setStep("harness");
  }, [step, report.npmPath, report.busy]);
  useEffect(() => {
    if (!pickedInstalled) sawMissing.current = true;
    else if (step === "harness" && sawMissing.current && !report.busy) setStep("sign-in");
  }, [pickedInstalled, step, report.busy]);
  useEffect(() => {
    if (step === "sign-in" && pick && pickedInstalled && credentialOk(pick, secrets, providers, catalog)) setStep("first-agent");
  }, [step, pick, pickedInstalled, secrets, providers, catalog]);

  const idx = SETUP_STEPS.findIndex((s) => s.id === step);
  const footer = (
    <>
      <Button variant="ghost" onClick={onClose}>
        {step === "first-agent" ? "Close" : "Not now"}
      </Button>
      {step === "harness" &&
        (pickedInstalled ? (
          <Button onClick={() => setStep("sign-in")}>Next</Button>
        ) : (
          <Button disabled={report.busy || !pick} onClick={() => pick && installHarnesses(stores().install, [pick])}>
            {report.busy ? "Installing..." : `Install ${picked?.label ?? ""}`.trim()}
          </Button>
        ))}
      {/* An optional sign-in never blocks: Continue tries the first agent, which says plainly if it cannot answer. */}
      {step === "sign-in" && <Button onClick={() => setStep("first-agent")}>Continue</Button>}
      {step === "first-agent" && <Button onClick={onClose}>Done</Button>}
    </>
  );
  return (
    <AppDialog open onOpenChange={() => {}} title={SETUP_STEPS[idx].title} description={`Step ${idx + 1} of ${SETUP_STEPS.length}`} footer={footer}>
      {step === "node" && <NodeStep report={report} recheck={recheck} />}
      {step === "harness" && <HarnessStep report={report} pick={pick} onPick={setPick} />}
      {step === "sign-in" && pick && <SignInStep id={pick} label={picked?.label || pick} rows={secrets} recheck={recheck} />}
      {step === "first-agent" && pick && <FirstAgentStep id={pick} secrets={secrets} providers={providers} catalog={catalog} />}
    </AppDialog>
  );
}
