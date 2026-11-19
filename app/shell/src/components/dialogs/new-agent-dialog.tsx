// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The New agent form (NewAgentDialog.kt): 1. a searchable grid of role cards, 2. harness, provider and model seeded
// from the role's binding, 3. the first task. The dialog host owns the draft so its Add button can act on it.
import { useEffect, useMemo, useState } from "react";
import { Check, SearchX } from "lucide-react";
import { useStore } from "zustand";
import {
  EMPTY_DRAFT,
  addBlockedReason,
  createLauncherStore,
  modelsFor,
  rankRoles,
  roleList,
  withHarness,
  withRole,
  type LauncherStore,
  type NewAgentDraft,
} from "@infantry/client";
import { Chip, EmptyState, Input, Textarea, cn } from "@infantry/ui";
import { daemonStore, useDaemon } from "@/daemon/daemon";
import { RoleAvatar } from "@/components/agents/agent-avatar";
import { RoleGlyph } from "@/components/agents/role-icon";
import { useRoles, useRolesLoaded } from "@/components/knowledge/use-knowledge";

export { EMPTY_DRAFT, addBlockedReason };
export type { NewAgentDraft };

let launcher: LauncherStore | null = null;
function launcherStore(): LauncherStore {
  return (launcher ??= createLauncherStore(daemonStore().getState()));
}

/** Loads the harness and model lists once the link is open. */
export function useLauncher() {
  const open = useDaemon((s) => s.connection.state) === "open";
  useEffect(() => {
    if (open) launcherStore().getState().ensureLoaded();
  }, [open]);
  return useStore(launcherStore(), (s) => s);
}

function Step({ n, title, missing, children }: { n: number; title: string; missing?: boolean; children: React.ReactNode }) {
  return (
    <section className="space-y-2" aria-label={title}>
      <h3 className="flex items-center gap-2 text-[13px] font-semibold">
        {n}. {title}
        {missing && <span className="text-[11px] font-normal text-danger">* required</span>}
      </h3>
      {children}
    </section>
  );
}

function Choice({ label, active, onPick, disabled }: { label: string; active: boolean; onPick: () => void; disabled?: boolean }) {
  return (
    <button
      type="button"
      aria-pressed={active}
      disabled={disabled}
      onClick={onPick}
      className={cn(
        "rounded-full border px-2.5 py-1 text-xs transition-colors focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none disabled:opacity-40",
        active ? "border-warn bg-warn/10 text-foreground" : "border-border text-muted-foreground hover:border-info/40 hover:text-foreground",
      )}
    >
      {label}
    </button>
  );
}

export function NewAgentBody({ draft, onChange, onSubmit }: { draft: NewAgentDraft; onChange: (d: NewAgentDraft) => void; onSubmit: () => void }) {
  useRolesLoaded();
  const { harnesses, catalog } = useLauncher();
  const rolesById = useRoles((s) => s.roles);
  const bindings = useRoles((s) => s.bindings);
  const loaded = useRoles((s) => s.loaded);
  const [query, setQuery] = useState("");
  const all = useMemo(() => roleList(rolesById).filter((r) => !r.disabled), [rolesById]);
  const options = useMemo(() => rankRoles(all, query), [all, query]);
  const groups = modelsFor(draft.harness, harnesses, catalog);
  const shown = groups.filter((g) => !draft.group || g.group === draft.group || groups.length === 1);
  const roleMissing = draft.roleId === null;

  // Catalogs that arrive after a role was picked seed the executor then, without losing the draft.
  useEffect(() => {
    if (draft.roleId && !draft.harness && harnesses.length > 0) {
      onChange(withRole(draft, draft.roleId, bindings[draft.roleId], harnesses, catalog));
    }
  }, [harnesses, catalog]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="space-y-5">
      <Step n={1} title="Role" missing={roleMissing}>
        <Input
          placeholder="Search roles..."
          aria-label="Search roles"
          autoFocus
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        {!loaded && all.length === 0 ? (
          <p className="text-xs text-muted-foreground">Loading roles from the daemon...</p>
        ) : options.length === 0 ? (
          <EmptyState icon={<SearchX className="size-5" />} title="No role matches" hint={query ? `Nothing named "${query}".` : "The daemon has no roles yet."} />
        ) : (
          <div role="listbox" aria-label="Roles" className="grid max-h-48 grid-cols-2 gap-1.5 overflow-y-auto pr-1">
            {options.map((r) => {
              const picked = r.id === draft.roleId;
              return (
                <button
                  key={r.id}
                  type="button"
                  role="option"
                  aria-selected={picked}
                  onClick={() => onChange(withRole(draft, r.id, bindings[r.id], harnesses, catalog))}
                  className={cn(
                    "flex min-w-0 items-center gap-2 rounded-lg border bg-card p-1.5 text-left transition-colors focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none",
                    picked ? "border-warn" : "border-info/25 hover:border-info/50",
                  )}
                >
                  <RoleAvatar roleId={r.id} size="md" />
                  <span className="min-w-0 flex-1">
                    <span className="flex items-center gap-1 text-[13px] font-medium">
                      <RoleGlyph icon={r.icon} className="size-3 shrink-0 text-muted-foreground" />
                      <span className="truncate">{r.name}</span>
                    </span>
                    <span className="block truncate font-mono text-[10px] text-muted-foreground">{r.id}</span>
                  </span>
                  {picked && <Check className="size-4 shrink-0 text-warn" aria-hidden />}
                </button>
              );
            })}
          </div>
        )}
      </Step>

      {draft.roleId && (
        <Step n={2} title="Harness, provider and model" missing={!draft.harness || !draft.model}>
          {harnesses.length === 0 ? (
            <p className="text-xs text-muted-foreground">The daemon has not listed its harnesses; the role's own pin is used.</p>
          ) : (
            <div className="flex flex-wrap gap-1.5" role="group" aria-label="Harness">
              {harnesses.map((h) => (
                <Choice
                  key={h.id}
                  label={h.id}
                  active={h.id === draft.harness}
                  disabled={h.available === false}
                  onPick={() => onChange(withHarness(draft, h.id, harnesses, catalog))}
                />
              ))}
            </div>
          )}
          {groups.length > 1 && (
            <div className="flex flex-wrap gap-1.5" role="group" aria-label="Provider">
              <Choice label="All" active={!draft.group} onPick={() => onChange({ ...draft, group: null })} />
              {groups.map((g) => (
                <Choice key={g.group} label={g.label} active={g.group === draft.group} onPick={() => onChange({ ...draft, group: g.group })} />
              ))}
            </div>
          )}
          <div className="flex flex-wrap gap-1.5" role="group" aria-label="Model">
            {shown.flatMap((g) =>
              g.models.map((m) => <Choice key={`${g.group}/${m}`} label={m} active={m === draft.model} onPick={() => onChange({ ...draft, model: m, group: g.group })} />),
            )}
            {shown.length === 0 && draft.model && <Chip tone="blue" mono>{draft.model}</Chip>}
          </div>
        </Step>
      )}

      <Step n={3} title="First task" missing={!draft.task.trim()}>
        <Textarea
          aria-label="First task"
          placeholder="What should this agent do first?"
          rows={3}
          value={draft.task}
          onChange={(e) => onChange({ ...draft, task: e.target.value })}
          onKeyDown={(e) => {
            if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) onSubmit();
          }}
        />
      </Step>
    </div>
  );
}


/** Add: the existing spawn with the picked role, harness and model, then the first task. */
export function addAgent(d: NewAgentDraft): void {
  const store = daemonStore().getState();
  const opts: Record<string, unknown> = { role: d.roleId };
  if (d.harness) opts.provider = d.harness;
  if (d.model) opts.model = d.model;
  if (store.spawnWith(opts)) store.sendFrame({ type: "prompt", data: d.task.trim() });
}
