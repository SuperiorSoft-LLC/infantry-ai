// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// ARCHIVE-RUST-2: the board's Archive view (Kotlin ArchiveView.kt parity): the project's archived tickets with search,
// the count, Restore per row; and the auto-archive setting (board.archive.doneAfterDays, picked, 0 = off).
// Plus the two Archive actions the board offers: one ticket (TicketArchiveButton) and one epic (EpicArchiveButton).
import { useEffect, useState } from "react";
import { Archive, ArchiveRestore } from "lucide-react";
import { LANE_TITLES, scopedBody, type Lane } from "@infantry/client";
import { AppDialog, Button, EmptyState, Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { useProjects } from "@/components/git/use-projects";
import { scopedStore, useQuery } from "@/components/panels/use-panels";
import { shortId } from "./ticket-card";
import { archiveStore, useArchive } from "./use-board";

export const AUTO_ARCHIVE_KEY = "board.archive.doneAfterDays";
const AUTO_DAYS = ["0", "7", "14", "30", "60", "90"];

/** The auto-archive picker: done tickets older than N days leave the board (the daemon checks hourly). */
export function AutoArchiveSetting() {
  const store = scopedStore();
  const link = useDaemon((s) => s.connection.state);
  const v = useQuery(store, (s) => s.data[AUTO_ARCHIVE_KEY]);
  useEffect(() => {
    if (link === "open") store.getState().send("settings_get", scopedBody(AUTO_ARCHIVE_KEY, {}));
  }, [link, store]);
  const days = String(Number(typeof v?.value === "string" ? v.value.replace(/"/g, "") : (v?.value ?? 0)) || 0);
  const options = AUTO_DAYS.includes(days) ? AUTO_DAYS : [...AUTO_DAYS, days];
  return (
    <div className="flex items-center gap-2 text-xs">
      <label htmlFor="auto-archive" className="text-muted-foreground">
        Archive done tickets older than
      </label>
      <Select value={days} onValueChange={(d) => store.getState().send("settings_set", scopedBody(AUTO_ARCHIVE_KEY, {}, { scope: "app", value: Number(d) }))}>
        <SelectTrigger id="auto-archive" aria-label="Auto-archive done tickets after" className="h-7 w-28 text-xs">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {options.map((d) => (
            <SelectItem key={d} value={d}>
              {d === "0" ? "Off" : `${d} days`}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}

export function ArchiveDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const project = useProjects((s) => s.currentId);
  const link = useDaemon((s) => s.connection.state);
  const items = useArchive((s) => s.items);
  const count = useArchive((s) => s.count);
  const loaded = useArchive((s) => s.loaded);
  const refusal = useArchive((s) => s.refusal);
  const [query, setQuery] = useState("");
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (open && link === "open") archiveStore().getState().load(query, project);
  }, [open, link, query, project]);
  const restore = (id: string) =>
    archiveStore()
      .getState()
      .apply({ op: "restore", ids: [id] }, (a) => setError(a.ok ? null : a.error));
  const problem = error ?? refusal;
  return (
    <AppDialog open={open} wide onOpenChange={(o) => !o && onClose()} title="Archive" description="Archived tickets keep everything. Restore puts one back where it was.">
      <div className="space-y-3">
        <AutoArchiveSetting />
        <Input aria-label="Search archived tickets" placeholder="Search archived tickets" className="h-8 text-xs" value={query} onChange={(e) => setQuery(e.target.value)} />
        <p className="text-xs text-muted-foreground" aria-live="polite">
          {loaded ? `${count} archived` : "Loading…"}
        </p>
        {problem && (
          <p role="alert" className="text-xs text-danger">
            {problem}
          </p>
        )}
        {loaded && items.length === 0 ? (
          <EmptyState icon={<Archive className="size-6" />} title={query.trim() ? "No archived ticket matches" : "No archived tickets"} />
        ) : (
          <ul className="max-h-[50vh] divide-y overflow-y-auto rounded-lg border">
            {items.map((t) => (
              <li key={t.id} className="flex items-center gap-2 px-3 py-2">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[13px]">{t.title}</p>
                  <p className="font-mono text-[11px] text-muted-foreground">
                    {shortId(t.id)} · {LANE_TITLES[t.status as Lane] ?? t.status}
                    {t.epicName ? ` · ${t.epicName}` : ""}
                  </p>
                </div>
                <Button size="sm" variant="outline" aria-label={`Restore ${t.title}`} onClick={() => restore(t.id)}>
                  <ArchiveRestore /> Restore
                </Button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </AppDialog>
  );
}

/** "Archive" on a ticket: disabled In Progress (with the reason), a second click confirms. */
export function TicketArchiveButton({ id, status, onArchived }: { id: string; status: string; onArchived: () => void }) {
  const [confirm, setConfirm] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const busy = status === "in_progress";
  const reason = busy ? "An In Progress ticket cannot be archived; finish or move it first." : "Move it off the board (Restore brings it back)";
  const click = () => {
    if (!confirm) return setConfirm(true);
    archiveStore()
      .getState()
      .apply({ op: "archive", ids: [id] }, (a) => (a.ok ? onArchived() : (setError(a.error), setConfirm(false))));
  };
  return (
    <span className="mr-1 flex items-center gap-2">
      <Button variant="ghost" disabled={busy} title={reason} onClick={click}>
        <Archive /> {confirm ? "Confirm archive" : "Archive"}
      </Button>
      {busy && <span className="text-xs text-muted-foreground">{reason}</span>}
      {error && (
        <span role="alert" className="text-xs text-danger">
          {error}
        </span>
      )}
    </span>
  );
}

/** "Archive epic": a dry run counts the tickets first, then a confirm archives the epic with them. */
export function EpicArchiveButton({ epicId, projectId }: { epicId: string; projectId?: string | null }) {
  const [state, setState] = useState<{ step: "idle" } | { step: "counting" } | { step: "confirm"; count: number } | { step: "done"; text: string } | { step: "error"; text: string }>({ step: "idle" });
  const body = { op: "archive" as const, epicId, ...(projectId ? { projectId } : {}) };
  const count = () => {
    setState({ step: "counting" });
    archiveStore()
      .getState()
      .apply({ ...body, dryRun: true }, (a) => setState(a.ok ? { step: "confirm", count: a.report.count } : { step: "error", text: a.error }));
  };
  const go = () =>
    archiveStore()
      .getState()
      .apply(body, (a) => setState(a.ok ? { step: "done", text: a.report.text || `archived ${a.report.count}` } : { step: "error", text: a.error }));
  if (state.step === "confirm")
    return (
      <span className="flex items-center gap-1 text-xs">
        <span>{state.count === 0 ? "Nothing to archive" : `Archive ${state.count} ticket${state.count === 1 ? "" : "s"}?`}</span>
        {state.count > 0 && (
          <Button size="sm" variant="outline" onClick={go}>
            Confirm
          </Button>
        )}
        <Button size="sm" variant="ghost" onClick={() => setState({ step: "idle" })}>
          Cancel
        </Button>
      </span>
    );
  return (
    <span className="flex items-center gap-1 text-xs">
      {(state.step === "done" || state.step === "error") && <span role={state.step === "error" ? "alert" : "status"} className={state.step === "error" ? "text-danger" : "text-muted-foreground"}>{state.text}</span>}
      <Button size="sm" variant="ghost" disabled={state.step === "counting"} onClick={count} title="Archive this epic and its tickets (In Progress tickets stay)">
        <Archive /> {state.step === "counting" ? "Counting…" : "Archive epic"}
      </Button>
    </span>
  );
}
