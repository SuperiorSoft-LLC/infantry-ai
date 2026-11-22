// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Runners card (RR-9): the runner list with each runner's status, Check / Rotate / Detach, and Add runner (two fields).
// Kit parts reused: PanelCard, AppDialog, Field, Input, Button, StatusDot, RefusalBanner, EmptyState, Chip.
import { useEffect, useState } from "react";
import { Info, Plug, Plus, RefreshCw, RotateCcw, ShieldCheck, Unplug, Wrench } from "lucide-react";
import { AppDialog, Button, EmptyState, Field, Input, PanelCard, RefusalBanner, StatusDot } from "@infantry/ui";
import { openRunnerSetup, useLinkState } from "@infantry/ui-plugin-api";
import { plainText, pingLine, runnersStore, statusOf, summaryLine, useRunners, type CheckRow, type RunnerDetail, type RunnerSummary, type RunnerView } from "./store";

/** Add runner: exactly two fields, the endpoint and the pasted bearer. `endpoint` prefills a Rotate. */
export function AddRunnerDialog({ open, endpoint, onClose }: { open: boolean; endpoint: string; onClose: () => void }) {
  const [url, setUrl] = useState(endpoint);
  const [bearer, setBearer] = useState("");
  const adding = useRunners((s) => s.adding);
  const error = useRunners((s) => s.addError);
  const [sent, setSent] = useState(false);
  useEffect(() => {
    if (open) {
      setUrl(endpoint);
      setBearer("");
      setSent(false);
    }
  }, [open, endpoint]);
  // A connect that came back without a refusal closed itself: the row is in the list.
  useEffect(() => {
    if (sent && !adding && !error) onClose();
  }, [sent, adding, error]); // eslint-disable-line react-hooks/exhaustive-deps
  const ready = url.trim() !== "" && bearer.trim() !== "" && !adding;
  const connect = () => {
    if (!ready) return;
    setSent(true);
    runnersStore().getState().add(url, bearer);
  };
  return (
    <AppDialog
      open={open}
      onOpenChange={(o) => !o && onClose()}
      title="Add runner"
      description="Paste the endpoint and the bearer the runner printed when it started."
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose}>
            Cancel
          </Button>
          <Button size="sm" disabled={!ready} onClick={connect}>
            {adding ? "Connecting…" : "Connect"}
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        <Field id="runner-endpoint" label="Endpoint">
          <Input id="runner-endpoint" placeholder="wss://host:port" autoComplete="off" spellCheck={false} value={url} onChange={(e) => setUrl(e.target.value)} />
        </Field>
        <Field id="runner-bearer" label="Bearer">
          <Input
            id="runner-bearer"
            type="password"
            placeholder="paste the bearer"
            autoComplete="off"
            spellCheck={false}
            value={bearer}
            onChange={(e) => setBearer(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && connect()}
          />
        </Field>
        {error && (
          <p role="alert" className="rounded-lg border border-danger/60 bg-danger/10 px-3 py-2 text-xs text-danger">
            {error}
          </p>
        )}
      </div>
    </AppDialog>
  );
}

function CheckRows({ rows }: { rows: CheckRow[] }) {
  if (rows.length === 0) return null;
  return (
    <ul aria-label="Check result" className="mt-2 space-y-1 border-t pt-2 text-xs">
      {rows.map((r, i) => (
        <li key={`${r.id}:${i}`} className="flex items-start gap-2">
          <span className={r.ok ? "text-ok" : "text-danger"} aria-label={r.ok ? "pass" : "fail"}>
            {r.ok ? "✓" : "✗"}
          </span>
          <span className="min-w-0">
            <span className="font-mono">{r.id}</span> <span className="text-muted-foreground">{r.detail}</span>
            {!r.ok && r.fix && <span className="block text-warn">Fix: {r.fix}</span>}
          </span>
        </li>
      ))}
    </ul>
  );
}

const when = (ms?: number) => (ms ? new Date(ms).toLocaleString() : "never");

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="rounded-lg border bg-card p-3">
      <h3 className="mb-1 text-xs font-medium">{title}</h3>
      <dl className="space-y-0.5 text-xs">{children}</dl>
    </section>
  );
}

function Line({ k, v }: { k: string; v?: React.ReactNode }) {
  return (
    <div className="flex gap-2">
      <dt className="w-28 shrink-0 text-muted-foreground">{k}</dt>
      <dd className="min-w-0 break-words font-mono">{v ?? "not reported"}</dd>
    </div>
  );
}

/** RR-VIS: how one runner is connected, in plain words: which runner, the link, its harnesses and logins, and the last check. */
export function DetailDialog({ name, detail, onClose }: { name: string; detail?: RunnerDetail; onClose: () => void }) {
  const r = detail?.runner;
  const l = detail?.link;
  const remote = (r?.mode ?? "").startsWith("remote");
  const place = (at: string | null) => (at ? (remote && /^(~|\/data)/.test(at) ? `${at} (inside the runner's /data volume)` : at) : "the harness did not say");
  return (
    <AppDialog open onOpenChange={(o) => !o && onClose()} title={`Runner ${name}`} description="How this runner is connected" footer={<Button variant="outline" size="sm" onClick={onClose}>Close</Button>}>
      {!detail ? (
        <p className="text-xs text-muted-foreground">Reading the runner…</p>
      ) : (
        <div className="space-y-2" data-testid="runner-detail">
          <Section title="Runner">
            <Line k="Name" v={r?.name} />
            <Line k="Runner id" v={r?.runnerId || (name === "local" ? "this app" : undefined)} />
            <Line k="Mode" v={r?.mode} />
            <Line k="Version" v={`app ${r?.appVersion ?? "not reported"} · contract ${r?.contract || "?"} (this app needs ${r?.needs ?? "?"})`} />
            {r?.os && <Line k="OS" v={r.os} />}
          </Section>
          <Section title="Connection">
            <Line k="Endpoint" v={l?.url || "in this app (no link)"} />
            {l?.fingerprint !== undefined && <Line k="Pinned cert" v={l.fingerprint || "none (plain link)"} />}
            {l?.pairedAt !== undefined && <Line k="Paired" v={`${when(l.pairedAt)} from ${l.pairedFrom || "not recorded (paired before this was kept)"}`} />}
            {l?.lastPingAt !== undefined && <Line k="Last ping" v={`${when(l.lastPingAt)}${typeof l.rttMs === "number" ? ` · ${l.rttMs} ms` : ""}`} />}
            <Line k="State" v={`${l?.state ?? "unknown"}${l?.reasonPlain ? ` — ${l.reasonPlain}` : ""}`} />
            {l?.reason && <Line k="Detail" v={l.reason} />}
            {l?.fix && <Line k="Next" v={l.fix} />}
          </Section>
          <Section title="Harnesses on this runner">
            {detail.harnessError && <p className="text-danger" data-testid="runner-harness-error">Not readable: {l?.state === "offline" ? `the runner is offline${l.reasonPlain ? ` (${l.reasonPlain})` : ""}` : detail.harnessError}. {l?.state === "offline" ? (l.fix ?? "") : "Update runner, then check again."}</p>}
            {!detail.harnesses && !detail.harnessError && <p className="text-muted-foreground">Reading…</p>}
            {detail.harnesses?.length === 0 && !detail.harnessError && <p className="text-muted-foreground">No harness reported.</p>}
            {detail.harnesses?.map((h) => (
              <div key={h.id} className="border-t pt-1" data-testid={`harness-${h.id}`}>
                <div className="font-medium">
                  {h.id} — {!h.installed ? "not installed" : h.signedIn === null ? "installed, sign-in unknown" : h.signedIn ? "installed, signed in" : "installed, NOT signed in"}
                </div>
                {h.installed && <Line k="Version" v={h.version ?? "not reported"} />}
                {h.installed && <Line k="Path" v={h.path} />}
                {h.installed && <Line k="Login at" v={place(h.loginAt)} />}
                {h.why && (
                  <p className="text-warn">
                    {h.why}. Sign in: Set up this runner → Sign in.
                  </p>
                )}
              </div>
            ))}
          </Section>
          <Section title="Setup log">
            {(detail.log ?? []).length === 0 && <p className="text-muted-foreground">Nothing has run on this runner yet.</p>}
            <ul aria-label="Setup log" className="max-h-48 space-y-1 overflow-auto font-mono">
              {(detail.log ?? []).map((e, i) => (
                <li key={`${e.at}:${i}`} className={e.exit ? "text-danger" : ""} data-testid="log-step">
                  {new Date(e.at).toLocaleTimeString()} · {e.runner} · {e.step} · <span className="text-muted-foreground">{e.command}</span> · exit {e.exit ?? "—"}
                  {e.output && <span className="block whitespace-pre-wrap text-muted-foreground">{plainText(e.output) ?? e.output}</span>}
                  {e.reason && <span className="block text-warn">{e.reason}</span>}
                </li>
              ))}
            </ul>
          </Section>
          <Section title="Runner log (tail)">
            {detail.runnerLogError && <p className="text-warn" data-testid="runner-log-error">{detail.runnerLogError}</p>}
            {detail.runnerLog && detail.runnerLog.length === 0 && <p className="text-muted-foreground">The runner has answered nothing yet.</p>}
            <pre aria-label="Runner log" className="max-h-40 overflow-auto whitespace-pre-wrap font-mono text-[11px] text-muted-foreground">{(detail.runnerLog ?? []).slice(-60).map((l) => l.replace(/^(\d{12,}) /, (_m, t: string) => `${new Date(Number(t)).toLocaleTimeString()} `)).join(String.fromCharCode(10))}</pre>
          </Section>
          <Section title="Last check">
            {detail.lastCheck ? (
              <>
                <Line k="When" v={when(detail.lastCheck.at)} />
                <CheckRows rows={detail.lastCheck.rows.map((c) => ({ ...c, detail: plainText(c.detail) ?? c.detail }))} />
              </>
            ) : (
              <p className="text-muted-foreground">No check yet: press Check.</p>
            )}
            {detail.update && <Line k="Update" v={`${detail.update.state}${detail.update.detail ? ` — ${detail.update.detail}` : ""}`} />}
          </Section>
        </div>
      )}
    </AppDialog>
  );
}

/** One runner: name and endpoint, the status dot with its reason, the contract and ping, the buttons. */
export function RunnerRow({ runner, rows, checking, summary, onCheck, onSetup, onRotate, onDetach, onUpdate, onDetails, note }: { runner: RunnerView; rows: CheckRow[]; checking: boolean; summary?: RunnerSummary; onCheck: () => void; onSetup: () => void; onRotate: () => void; onDetach: () => void; onUpdate?: () => void; onDetails?: () => void; note?: string }) {
  const st = statusOf(runner);
  const local = runner.name === "local";
  const ping = pingLine(runner);
  return (
    <li className="rounded-lg border bg-card p-3" data-runner={runner.name}>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0 space-y-0.5">
          <div className="flex items-center gap-2 text-[13px] font-medium">
            <StatusDot status={st.dot} />
            <span className="truncate">{runner.name}</span>
            <span className="text-xs font-normal text-muted-foreground">{st.word}</span>
            {local && <span className="rounded border px-1 text-[10px] font-normal text-muted-foreground">embedded</span>}
          </div>
          <p className="truncate font-mono text-[11px] text-muted-foreground">{local ? "in this app (no link)" : runner.url}</p>
          {st.reason && <p className="text-xs text-danger">{st.reason}</p>}
          {ping && <p className="text-[11px] text-muted-foreground">{ping}</p>}
          {runner.update && (
            <p className={`text-[11px] ${runner.update.state === "failed" ? "text-danger" : "text-muted-foreground"}`} data-testid="runner-update">
              Update: {runner.update.state}
              {runner.update.state === "running" ? ` — ${runner.update.steps[runner.update.steps.length - 1] ?? "starting"}` : runner.update.detail ? ` — ${runner.update.detail}` : ""}
            </p>
          )}
          {note && !runner.update && <p className="text-[11px] text-muted-foreground">{note}</p>}
          {summary && st.word !== "offline" && (
            <p className="text-[11px] text-muted-foreground" data-testid="runner-summary">
              {summaryLine(summary)}
            </p>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <Button variant="outline" size="xs" disabled={checking} onClick={onCheck}>
            <ShieldCheck className="size-3" /> {checking ? "Checking…" : "Check"}
          </Button>
          {onDetails && (
            <Button variant="outline" size="xs" onClick={onDetails} title="How this runner is connected">
              <Info className="size-3" /> Details
            </Button>
          )}
          <Button variant="outline" size="xs" onClick={onSetup} title="Install tools, sign in and set keys on this runner">
            <Wrench className="size-3" /> Set up this runner
          </Button>
          {!local && (
            <>
              {onUpdate && (
                <Button variant={runner.outdated ? "default" : "outline"} size="xs" disabled={runner.update?.state === "running"} onClick={onUpdate} title="Recreate this runner on the app's own version">
                  <RefreshCw className="size-3" /> Update runner
                </Button>
              )}
              <Button variant="outline" size="xs" onClick={onRotate} title="Pair again with a new bearer">
                <RotateCcw className="size-3" /> Rotate
              </Button>
              <Button variant="outline" size="xs" onClick={onDetach}>
                <Unplug className="size-3" /> Detach
              </Button>
            </>
          )}
        </div>
      </div>
      <CheckRows rows={rows} />
    </li>
  );
}

export function RunnersCard() {
  const link = useLinkState();
  const runners = useRunners((s) => s.runners);
  const loaded = useRunners((s) => s.loaded);
  const checks = useRunners((s) => s.checks);
  const checking = useRunners((s) => s.checking);
  const updateNote = useRunners((s) => s.updateNote);
  const summaries = useRunners((s) => s.summaries);
  const refusal = useRunners((s) => s.refusal);
  const details = useRunners((s) => s.details);
  const [dialog, setDialog] = useState<{ endpoint: string } | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  // The setup log is live: while the Details dialog is open the runner is re-read every 2 s.
  useEffect(() => {
    if (!open) return;
    const t = setInterval(() => runnersStore().getState().detail(open), 2000);
    return () => clearInterval(t);
  }, [open]);
  useEffect(() => {
    if (link === "open") runnersStore().getState().refresh();
  }, [link]);
  // One read of each reachable runner's machine for the row summary (an offline runner is not asked: it would only time out).
  useEffect(() => {
    for (const r of runners) if (r.state !== "offline" && !(r.name in summaries)) runnersStore().getState().summarize(r.name);
  }, [runners, summaries]);
  const store = runnersStore().getState();
  return (
    <PanelCard
      title="Runners"
      action={
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="xs" aria-label="Refresh runners" onClick={() => store.refresh()}>
            <RefreshCw className="size-3" />
          </Button>
          <Button size="xs" onClick={() => setDialog({ endpoint: "" })}>
            <Plus className="size-3" /> Add runner
          </Button>
        </div>
      }
    >
      <RefusalBanner text={refusal} onDismiss={() => store.clearRefusal()} />
      {!loaded ? (
        <p className="text-xs text-muted-foreground">{link === "open" ? "Loading runners…" : "Waiting for the daemon…"}</p>
      ) : runners.length === 0 ? (
        <EmptyState icon={<Plug className="size-5" />} title="No runners" hint="Add runner pairs one; the embedded local runner is always there." />
      ) : (
        <ul className="space-y-2" aria-label="Runners">
          {runners.map((r) => (
            <RunnerRow
              key={r.name}
              runner={r}
              rows={checks[r.name] ?? []}
              checking={checking === r.name}
              summary={summaries[r.name]}
              onCheck={() => store.check(r.name)}
              onSetup={() => openRunnerSetup(r.name)}
              onRotate={() => setDialog({ endpoint: r.url })}
              onDetach={() => store.detach(r.name)}
              onUpdate={() => store.update(r.name)}
              onDetails={() => {
                setOpen(r.name);
                store.detail(r.name);
              }}
              note={updateNote[r.name]}
            />
          ))}
        </ul>
      )}
      {open && <DetailDialog name={open} detail={details[open]} onClose={() => setOpen(null)} />}
      <AddRunnerDialog open={dialog !== null} endpoint={dialog?.endpoint ?? ""} onClose={() => setDialog(null)} />
    </PanelCard>
  );
}
