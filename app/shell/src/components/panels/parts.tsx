// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Shared bits of the RUST-UI-7 panels: the refresh button, a project picker filled from the daemon's project list, and the
// one "not served" view (the daemon's own refusal text, plus the frame the panel needs).
import type { ReactNode } from "react";
import { CircleSlash, RefreshCw } from "lucide-react";
import { isUnserved, type QueryStore } from "@infantry/client";
import { Button, EmptyState, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { RefusalBanner } from "@/components/knowledge/parts";
import { useQuery } from "./use-panels";

export function RefreshButton<T>({ store, body }: { store: QueryStore<T>; body?: string }) {
  const loading = useQuery(store, (s) => s.loading);
  return (
    <Button variant="ghost" size="sm" aria-label="Refresh" disabled={loading} onClick={() => store.getState().refresh(body)}>
      <RefreshCw className={loading ? "animate-spin" : ""} />
    </Button>
  );
}

/** A labelled dropdown over [options] (a project, a role, a session). */
export function Picker({ label, value, options, onChange }: { label: string; value: string | null; options: { id: string; name: string }[]; onChange: (v: string) => void }) {
  return (
    <Select value={value ?? undefined} onValueChange={onChange}>
      <SelectTrigger aria-label={label} className="h-8 w-48 text-xs">
        <SelectValue placeholder={options.length ? label : `No ${label.toLowerCase()}`} />
      </SelectTrigger>
      <SelectContent>
        {options.map((o) => (
          <SelectItem key={o.id} value={o.id}>
            {o.name}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/** The projects the daemon knows, as a picker; the first one is the default. */
export function ProjectPicker({ value, onChange }: { value: string | null; onChange: (id: string) => void }) {
  const projects = useDaemon((s) => s.projects);
  if (projects.length === 0) return null;
  return (
    <Select value={value ?? undefined} onValueChange={onChange}>
      <SelectTrigger aria-label="Project" className="h-8 w-48 text-xs">
        <SelectValue placeholder="Project" />
      </SelectTrigger>
      <SelectContent>
        {projects.map((p) => (
          <SelectItem key={p.id} value={p.id}>
            {p.name || p.id}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

/**
 * The panel body while [store] has no data of its own: a clean "not served" state when the daemon has no unit for the frame,
 * a dismissible banner for any other refusal, or [children].
 */
export function Served<T>({ store, frames, children, banner = true }: { store: QueryStore<T>; frames: string; children: ReactNode; banner?: boolean }) {
  const refusal = useQuery(store, (s) => s.refusal);
  const link = useDaemon((s) => s.connection.state);
  if (isUnserved(refusal))
    return <EmptyState icon={<CircleSlash className="size-6" />} title="This daemon does not serve this panel yet" hint={`${refusal} (needs: ${frames})`} />;
  if (link !== "open") return <EmptyState icon={<RefreshCw className="size-6" />} title="Waiting for the daemon…" />;
  return (
    <>
      {banner && <RefusalBanner text={refusal} onDismiss={() => store.getState().clearRefusal()} />}
      {children}
    </>
  );
}
