// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Runners card (RR-9): the runner list with each runner's status, Check / Rotate / Detach, and Add runner (two fields).
// Kit parts reused: PanelCard, AppDialog, Field, Input, Button, StatusDot, RefusalBanner, EmptyState, Chip.
import { useEffect, useState } from "react";
import { Plug, Plus, RefreshCw, RotateCcw, ShieldCheck, Unplug, Wrench } from "lucide-react";
import { AppDialog, Button, EmptyState, Field, Input, PanelCard, RefusalBanner, StatusDot } from "@infantry/ui";
import { openRunnerSetup, useLinkState } from "@infantry/ui-plugin-api";
import { pingLine, runnersStore, statusOf, summaryLine, useRunners, type CheckRow, type RunnerSummary, type RunnerView } from "./store";

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

/** One runner: name and endpoint, the status dot with its reason, the contract and ping, the buttons. */
export function RunnerRow({ runner, rows, checking, summary, onCheck, onSetup, onRotate, onDetach }: { runner: RunnerView; rows: CheckRow[]; checking: boolean; summary?: RunnerSummary; onCheck: () => void; onSetup: () => void; onRotate: () => void; onDetach: () => void }) {
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
          {summary && (
            <p className="text-[11px] text-muted-foreground" data-testid="runner-summary">
              {summaryLine(summary)}
            </p>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <Button variant="outline" size="xs" disabled={checking} onClick={onCheck}>
            <ShieldCheck className="size-3" /> {checking ? "Checking…" : "Check"}
          </Button>
          <Button variant="outline" size="xs" onClick={onSetup} title="Install tools, sign in and set keys on this runner">
            <Wrench className="size-3" /> Set up this runner
          </Button>
          {!local && (
            <>
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
  const summaries = useRunners((s) => s.summaries);
  const refusal = useRunners((s) => s.refusal);
  const [dialog, setDialog] = useState<{ endpoint: string } | null>(null);
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
            />
          ))}
        </ul>
      )}
      <AddRunnerDialog open={dialog !== null} endpoint={dialog?.endpoint ?? ""} onClose={() => setDialog(null)} />
    </PanelCard>
  );
}
