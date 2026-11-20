// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The guided first-time setup (RUST PARITY of Kotlin ShellSetupWizard). Opens by itself on every app start while nothing
// can answer (`needed`), and from Settings > Daemon at any time. One choice per screen:
//  1. Node.js (only while npm is missing and nothing is installed): the daemon installs it into the app's own folder.
//  2. Pick an agent tool the daemon's installer offers and install it (plain failure + retry).
//  3. Sign in (skipped when the tool can already answer: a login, a key or free models).
//  4. Start a first agent in a starter project and show its reply.
// Every decision is `@infantry/client` setup-wizard.ts (tested); this file draws the daemon's answers.
import { useCallback, useEffect, useRef, useState } from "react";
import { useStore } from "zustand";
import {
  createHarnessInstallStore,
  createQueryStore,
  defaultPick,
  credentialOk,
  firstModel,
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
  type SetupStep,
} from "@infantry/client";
import { AppDialog, Button, cn } from "@infantry/ui";
import { daemonStore, useDaemon } from "@/daemon/daemon";
import { useUi } from "@/state/ui-state";

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
  const report = useStore(s.install, (x) => x.data);
  const providersLoaded = useStore(s.providers, (x) => x.loaded);
  const providers = useStore(s.providers, (x) => x.data);
  const catalog = useStore(s.catalog, (x) => x.data);
  const recheck = useCallback(() => {
    s.install.getState().refresh();
    s.providers.getState().refresh();
    s.catalog.getState().refresh();
  }, [s.install, s.providers, s.catalog]);
  useEffect(() => {
    if (open) recheck();
  }, [open, recheck]);
  return { report, providers: providersLoaded ? providers : null, catalog, recheck };
}

/** Opens the setup by itself, once per app start, when nothing can answer. Never on a guess (null = not answered yet). */
export function useAutoSetup(): void {
  const ui = useUi();
  const { report, providers, catalog } = useSetupData();
  const decided = useRef(false);
  const want = needed(report, providers, catalog);
  useEffect(() => {
    if (decided.current || want === null) return;
    decided.current = true;
    if (want && !ui.dialog) ui.set("dialog", "setup");
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
          <Log lines={report.log} />
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
            <Button size="sm" variant="outline" onClick={() => window.open("https://nodejs.org/en/download", "_blank")}>
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
      {report.busy ? <p className="text-xs text-muted-foreground">Installing. This can take a few minutes.</p> : null}
      <Log lines={report.log} />
      {!report.busy && outcome && !outcome.ok && (
        <p role="alert" className="text-xs text-danger">
          {outcome.text} Press Install to try again.
        </p>
      )}
    </div>
  );
}

function SignInStep({ id, provider, recheck }: { id: string; provider?: HarnessInfo; recheck: () => void }) {
  return (
    <div className="space-y-2.5">
      <p className="text-[13px]">Connect {id} so your agent can answer.</p>
      <p className="text-xs text-muted-foreground">{provider?.unavailableReason ?? "It is not signed in yet."}</p>
      <p className="text-xs text-muted-foreground">
        Open a terminal and start <span className="font-mono">{id}</span>. It asks you to sign in (a browser window opens). Come back here and press Check again.
      </p>
      <Button size="sm" onClick={recheck}>
        Check again
      </Button>
    </div>
  );
}

/** The project the first agent runs in: the first there is, else a starter the daemon makes (zero typing). */
function FirstAgentStep({ id, providers, catalog }: { id: string; providers: HarnessInfo[]; catalog: CatalogGroup[] }) {
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
    const model = firstModel(id, providers, catalog);
    if (model) opts.model = model;
    if (project.directory) opts.cwd = project.directory;
    if (!store.spawnWith(opts)) setError("the daemon link is down");
  }, [projects, id, providers, catalog]);
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
  const { report, providers, catalog, recheck } = useSetupData();
  if (!report) return <AppDialog open onOpenChange={(o) => !o && onClose()} title="Setup" description="Asking the daemon what is installed..." footer={null}>{null}</AppDialog>;
  return <Wizard report={report} providers={providers ?? []} catalog={catalog} recheck={recheck} onClose={onClose} />;
}

function Wizard({ report, providers, catalog, recheck, onClose }: { report: HarnessInstallReport; providers: HarnessInfo[]; catalog: CatalogGroup[]; recheck: () => void; onClose: () => void }) {
  const [step, setStep] = useState<SetupStep>(() => firstStep(report));
  const [pick, setPick] = useState<string | null>(() => defaultPick(report));
  const picked = report.harnesses.find((h) => h.id === pick);
  const pickedInstalled = !!picked && installed(picked);
  const wasBusy = useRef(false);
  const sawMissing = useRef(false);

  // While an install runs the daemon's log is the progress: read it again every 1.5 s.
  useEffect(() => {
    if (!report.busy) return;
    const t = setInterval(() => stores().install.getState().refresh(), 1500);
    return () => clearInterval(t);
  }, [report.busy]);
  // An install finished: re-read the harness list and models (they show the new tool and its free models).
  useEffect(() => {
    if (wasBusy.current && !report.busy) {
      stores().providers.getState().refresh();
      stores().catalog.getState().refresh();
    }
    wasBusy.current = report.busy;
  }, [report.busy]);
  // Step changes follow the daemon's answers, never a guess: Node found -> the pick; installed -> sign-in; can answer -> first agent.
  useEffect(() => {
    if (step === "node" && report.npmPath && !report.busy) setStep("harness");
  }, [step, report.npmPath, report.busy]);
  useEffect(() => {
    if (!pickedInstalled) sawMissing.current = true;
    else if (step === "harness" && sawMissing.current && !report.busy) setStep("sign-in");
  }, [pickedInstalled, step, report.busy]);
  useEffect(() => {
    if (step === "sign-in" && pick && credentialOk(pick, providers, catalog)) setStep("first-agent");
  }, [step, pick, providers, catalog]);

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
    <AppDialog open onOpenChange={(o) => !o && onClose()} title={SETUP_STEPS[idx].title} description={`Step ${idx + 1} of ${SETUP_STEPS.length}`} footer={footer}>
      {step === "node" && <NodeStep report={report} recheck={recheck} />}
      {step === "harness" && <HarnessStep report={report} pick={pick} onPick={setPick} />}
      {step === "sign-in" && pick && <SignInStep id={pick} provider={providers.find((p) => p.id === pick)} recheck={recheck} />}
      {step === "first-agent" && pick && <FirstAgentStep id={pick} providers={providers} catalog={catalog} />}
    </AppDialog>
  );
}
