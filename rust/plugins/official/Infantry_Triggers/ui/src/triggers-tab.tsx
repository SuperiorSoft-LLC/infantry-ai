// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Triggers tab (RUST-UI-5; Kotlin `infantry.triggers`; a web UI plugin since WEBUI-1, drawn as before): every
// trigger as a bordered card with its state, schedule, action and last/next firing; Fire, Arm (a tripped one), Disable,
// Delete and its event log. A module's trigger (it has an `owner`) is read-only. New trigger asks only for a name; kind
// and interval are pickers with working defaults.
import { useEffect, useState } from "react";
import { ChevronDown, ChevronRight, Lock, Pencil, Play, Plus, Power, RefreshCw, RotateCcw, Trash2, Zap } from "lucide-react";
import { AppDialog, Button, Chip, EmptyState, Field, Input, RefusalBanner, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, TabPage, Textarea, ago, type Tone } from "@infantry/ui";
import { useLinkState } from "@infantry/ui-plugin-api";
import { triggersStore, useTriggers, type TriggerView } from "./store";

const INTERVALS: { ms: number; label: string }[] = [
  { ms: 900_000, label: "Every 15 minutes" },
  { ms: 3_600_000, label: "Every hour" },
  { ms: 21_600_000, label: "Every 6 hours" },
  { ms: 86_400_000, label: "Every day" },
];

function stateTone(state: string): Tone {
  switch (state) {
    case "armed":
      return "green";
    case "running":
      return "blue";
    case "tripped":
      return "red";
    default:
      return "neutral";
  }
}

function every(ms?: number | null): string {
  if (!ms) return "";
  return INTERVALS.find((i) => i.ms === ms)?.label.toLowerCase() ?? `every ${Math.round(ms / 60_000)} min`;
}

function until(at?: number | null): string {
  if (!at) return "";
  const m = Math.round((at - Date.now()) / 60_000);
  return m <= 0 ? "due now" : m < 60 ? `in ${m} min` : `in ${Math.round(m / 60)} h`;
}

function Events({ id }: { id: string }) {
  const rows = useTriggers((s) => s.events[id]);
  useEffect(() => triggersStore().getState().loadEvents(id), [id]);
  if (!rows) return <p className="text-xs text-muted-foreground">Loading events…</p>;
  if (rows.length === 0) return <p className="text-xs text-muted-foreground">No events yet.</p>;
  return (
    <ul className="max-h-48 space-y-0.5 overflow-auto font-mono text-[11px]">
      {[...rows].reverse().map((e, i) => (
        <li key={i} className="flex gap-2">
          <span className="w-16 shrink-0 text-muted-foreground">{ago(e.at)}</span>
          <span>{e.disposition}</span>
          {e.source && <span className="text-muted-foreground">{e.source}</span>}
        </li>
      ))}
    </ul>
  );
}

function TriggerCard({ t, onEdit }: { t: TriggerView; onEdit: (t: TriggerView) => void }) {
  const [open, setOpen] = useState(false);
  const s = () => triggersStore().getState();
  const owned = !!t.owner;
  const action = t.action.kind === "prompt" ? `prompt ${t.action.agentId ?? ""}` : `start next ticket${t.action.agentId ? ` for ${t.action.agentId}` : ""}`;
  return (
    <article className="flex flex-col gap-2 rounded-lg border bg-card p-4" aria-label={t.name}>
      <div className="flex items-start gap-3">
        <div className="grid size-9 shrink-0 place-items-center rounded-md border bg-raised text-danger">
          <Zap className="size-4" />
        </div>
        <div className="min-w-0 flex-1">
          <h3 className="flex items-center gap-1.5 truncate font-semibold">
            {t.name}
            {owned && <Lock className="size-3.5 text-muted-foreground" aria-label={`Owned by ${t.owner}`} />}
          </h3>
          <p className="truncate text-xs text-muted-foreground">
            {t.kind}
            {t.kind === "schedule" && t.intervalMs ? ` · ${every(t.intervalMs)}` : ""} · {action}
          </p>
        </div>
        <Chip tone={stateTone(t.state)}>{t.state}</Chip>
      </div>
      {t.state === "tripped" && t.trippedReason && <p className="rounded-md border border-danger/60 bg-danger/10 px-2 py-1 text-xs text-danger">{t.trippedReason}</p>}
      <p className="text-xs text-muted-foreground">
        last fired {t.lastFiredAt ? ago(t.lastFiredAt) : "never"}
        {t.nextFireAt ? ` · next ${until(t.nextFireAt)}` : ""}
        {owned ? ` · owned by ${t.owner}` : ""}
      </p>
      <div className="flex flex-wrap items-center gap-1">
        <Button variant="outline" size="xs" disabled={!t.enabled || t.state === "tripped"} onClick={() => s().fire(t.id)}>
          <Play /> Fire
        </Button>
        {!owned && (t.state === "tripped" || !t.enabled) && (
          <Button variant="ghost" size="xs" onClick={() => s().arm(t.id)}>
            <RotateCcw /> Arm
          </Button>
        )}
        {!owned && t.enabled && t.state !== "tripped" && (
          <Button variant="ghost" size="xs" onClick={() => s().disable(t.id)}>
            <Power /> Disable
          </Button>
        )}
        <span className="flex-1" />
        <Button variant="ghost" size="xs" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
          {open ? <ChevronDown /> : <ChevronRight />} Events
        </Button>
        {!owned && (
          <Button variant="ghost" size="xs" aria-label={`Edit ${t.name}`} onClick={() => onEdit(t)}>
            <Pencil />
          </Button>
        )}
        {!owned && (
          <Button variant="ghost" size="xs" aria-label={`Delete ${t.name}`} onClick={() => s().remove(t.id)}>
            <Trash2 />
          </Button>
        )}
      </div>
      {open && <Events id={t.id} />}
    </article>
  );
}

/** New trigger (`edit` unset) or edit of an existing one: every field the save frame carries. */
function NewTriggerDialog({ open, onClose, edit }: { open: boolean; onClose: () => void; edit?: TriggerView | null }) {
  const [name, setName] = useState("");
  const [kind, setKind] = useState<"manual" | "schedule">("manual");
  const [interval, setInterval] = useState(String(INTERVALS[1].ms));
  const [actionKind, setActionKind] = useState("start_next");
  const [agentId, setAgentId] = useState("");
  const [prompt, setPrompt] = useState("");
  useEffect(() => {
    if (!open) return;
    setName(edit?.name ?? "");
    setKind(edit?.kind === "schedule" ? "schedule" : "manual");
    setInterval(String(edit?.intervalMs ?? INTERVALS[1].ms));
    setActionKind(edit?.action.kind ?? "start_next");
    setAgentId(edit?.action.agentId ?? "");
    setPrompt(edit?.action.prompt ?? "");
  }, [open, edit]);
  const submit = () => {
    if (!name.trim()) return;
    triggersStore().getState().save({
      id: edit?.id,
      name,
      kind,
      intervalMs: kind === "schedule" ? Number(interval) : undefined,
      enabled: edit?.enabled,
      coalesceMs: edit?.coalesceMs,
      ceilingRuns: edit?.ceilingRuns,
      ceilingWindowMs: edit?.ceilingWindowMs,
      projectId: edit?.projectId,
      action: { kind: actionKind, agentId: agentId.trim() || null, prompt: actionKind === "prompt" ? prompt : null },
    });
    onClose();
  };
  return (
    <AppDialog
      open={open}
      onOpenChange={(o) => !o && onClose()}
      title={edit ? "Edit trigger" : "New trigger"}
      description={edit ? "Change what it fires and when" : "Starts the next ready ticket when it fires"}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button disabled={!name.trim()} onClick={submit}>
            {edit ? "Save" : "Create"}
          </Button>
        </>
      }
    >
      <form
        className="space-y-3 p-4"
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        <Field id="trigger-name" label="Name">
          <Input id="trigger-name" autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="Nightly tickets" />
        </Field>
        <Field id="trigger-kind" label="Fires">
          <Select value={kind} onValueChange={(v) => setKind(v as "manual" | "schedule")}>
            <SelectTrigger id="trigger-kind" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="manual">When I press Fire</SelectItem>
              <SelectItem value="schedule">On a schedule</SelectItem>
            </SelectContent>
          </Select>
        </Field>
        {kind === "schedule" && (
          <Field id="trigger-interval" label="Interval">
            <Select value={interval} onValueChange={setInterval}>
              <SelectTrigger id="trigger-interval" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {INTERVALS.map((i) => (
                  <SelectItem key={i.ms} value={String(i.ms)}>
                    {i.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>
        )}
        <Field id="trigger-action" label="Action">
          <Select value={actionKind} onValueChange={setActionKind}>
            <SelectTrigger id="trigger-action" className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="start_next">Start the next ready ticket</SelectItem>
              <SelectItem value="prompt">Send a prompt to an agent</SelectItem>
            </SelectContent>
          </Select>
        </Field>
        <Field id="trigger-agent" label="Target agent">
          <Input id="trigger-agent" value={agentId} onChange={(e) => setAgentId(e.target.value)} placeholder="Optional agent id or role" />
        </Field>
        {actionKind === "prompt" && (
          <Field id="trigger-prompt" label="Message">
            <Textarea id="trigger-prompt" rows={4} value={prompt} onChange={(e) => setPrompt(e.target.value)} placeholder="What the agent is told when it fires" />
          </Field>
        )}
      </form>
    </AppDialog>
  );
}

export function TriggersTab() {
  const triggers = useTriggers((s) => s.triggers);
  const loaded = useTriggers((s) => s.loaded);
  const refusal = useTriggers((s) => s.refusal);
  const link = useLinkState();
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<TriggerView | null>(null);
  useEffect(() => {
    if (link === "open") triggersStore().getState().refresh();
  }, [link]);
  return (
    <TabPage
      title="Triggers"
      description="Events that start work: by hand, on a schedule, or from a module"
      actions={
        <>
          <Button variant="ghost" size="sm" aria-label="Refresh" onClick={() => triggersStore().getState().refresh()}>
            <RefreshCw />
          </Button>
          <Button variant="outline" size="sm" onClick={() => setCreating(true)}>
            <Plus /> New trigger
          </Button>
        </>
      }
    >
      <RefusalBanner text={refusal} onDismiss={() => triggersStore().getState().clearRefusal()} />
      {!loaded ? (
        <EmptyState icon={<Zap className="size-6" />} title={link === "open" ? "Loading triggers…" : "Waiting for the daemon…"} />
      ) : triggers.length === 0 ? (
        <EmptyState icon={<Zap className="size-6" />} title="No triggers yet" hint="A trigger starts the next ready ticket when it fires." />
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(300px,1fr))] gap-3">
          {triggers.map((t) => (
            <TriggerCard key={t.id} t={t} onEdit={(x) => (setEditing(x), setCreating(true))} />
          ))}
        </div>
      )}
      <NewTriggerDialog open={creating} edit={editing} onClose={() => (setCreating(false), setEditing(null))} />
    </TabPage>
  );
}
