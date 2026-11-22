// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Settings page (RUST-UI-7, Kotlin's `infantry.settings` tab), across every settings scope:
//   App      the declared cards (the same DeclaredSettings the Settings dialog draws), by tab, with search.
//   Project / Role / Session   a picker for the target (filled from the daemon: projects, roles, sessions; nothing typed),
//            then each declared field that takes a scope, resolved by `settings_get` with its layers
//            (session > role > project > app > default); a value is set or cleared at the picked scope by `settings_set`.
import { useEffect, useMemo, useState } from "react";
import { SlidersHorizontal } from "lucide-react";
import { roleList, scopedBody, type ConfigField, type ScopeTarget } from "@infantry/client";
import { Button, Chip, EmptyState, Input, PanelCard, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Switch } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { TabPage } from "@/components/tabs/tab-page";
import { Segmented } from "@/components/knowledge/parts";
import { useRoles, useRolesLoaded } from "@/components/knowledge/use-knowledge";
import { DeclaredSettings, SettingsSearch, useDeclaredTabs } from "@/components/settings/declared-settings";
import { useSettings } from "@/components/settings/use-settings";
import { Picker, Served } from "./parts";
import { scopedStore, useQuery } from "./use-panels";

type Scope = "app" | "project" | "role" | "session";
const SCOPES: { id: Scope; label: string }[] = [
  { id: "app", label: "App" },
  { id: "project", label: "Project" },
  { id: "role", label: "Role" },
  { id: "session", label: "Session" },
];

const show = (v: unknown): string => (v === null || v === undefined ? "—" : typeof v === "string" ? v : JSON.stringify(v));

/** One field at the picked scope: its resolved value, where it comes from, and an editor for this scope's layer. */
function ScopedRow({ field, scope, target }: { field: ConfigField; scope: Exclude<Scope, "app">; target: ScopeTarget }) {
  const store = scopedStore();
  const v = useQuery(store, (s) => s.data[field.key]);
  const here = v?.layers.find((l) => l.scope === scope);
  const [draft, setDraft] = useState("");
  const write = (value: unknown) => store.getState().send("settings_set", scopedBody(field.key, target, { scope, value }));
  const editor =
    field.type === "boolean" ? (
      <Switch aria-label={field.label ?? field.key} checked={here ? here.value === true : v?.value === true || (v?.value == null && field.default === true)} onCheckedChange={write} />
    ) : field.options?.length ? (
      <Select value={here ? String(here.value) : undefined} onValueChange={write}>
        <SelectTrigger aria-label={field.label ?? field.key} className="h-7 w-40 text-xs">
          <SelectValue placeholder="Inherit" />
        </SelectTrigger>
        <SelectContent>
          {field.options.map((o) => (
            <SelectItem key={o} value={o}>
              {field.optionLabels?.[o] ?? o}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    ) : (
      <form
        className="flex gap-1"
        onSubmit={(e) => {
          e.preventDefault();
          if (!draft.trim()) return;
          write(field.type === "number" || field.type === "int" ? Number(draft) : draft);
          setDraft("");
        }}
      >
        <Input aria-label={field.label ?? field.key} className="h-7 w-28 text-xs" placeholder={show(here?.value ?? v?.value ?? field.default)} value={draft} onChange={(e) => setDraft(e.target.value)} />
      </form>
    );
  return (
    <li className="flex flex-wrap items-center gap-2 py-1.5 text-[13px]">
      <div className="min-w-0 flex-1">
        <div className="truncate font-medium">{field.label ?? field.key}</div>
        <div className="truncate font-mono text-[11px] text-muted-foreground">
          {field.key} = {show(v ? v.value ?? field.default : field.default)}
        </div>
      </div>
      <Chip tone={v && v.scope === scope ? "blue" : "neutral"}>{v ? `from ${v.scope}` : "…"}</Chip>
      {editor}
      {here && (
        <Button variant="ghost" size="xs" onClick={() => write(null)}>
          Clear
        </Button>
      )}
    </li>
  );
}

function ScopedSettings({ scope }: { scope: Exclude<Scope, "app"> }) {
  useRolesLoaded();
  const projects = useDaemon((s) => s.projects);
  const sessions = useDaemon((s) => s.sessions);
  const roles = useRoles((s) => s.roles);
  const groups = useSettings((s) => s.groups);
  const [project, setProject] = useState<string | null>(null);
  const [role, setRole] = useState<string | null>(null);
  const [session, setSession] = useState<string | null>(null);
  const p = project ?? projects[0]?.id ?? null;
  const target: ScopeTarget = scope === "session" ? { session: session ?? undefined } : scope === "role" ? { project: p ?? undefined, role: role ?? undefined } : { project: p ?? undefined };
  const ready = scope === "session" ? !!session : scope === "role" ? !!p && !!role : !!p;
  // A field takes a scope when it declares one; the rest are app-wide.
  const fields = useMemo(() => groups.flatMap((g) => (g.fields ?? []).filter((f) => !!f.scope && f.scope !== "app")), [groups]);
  const key = JSON.stringify(target);
  const link = useDaemon((s) => s.connection.state);
  useEffect(() => {
    if (link !== "open" || !ready) return;
    const store = scopedStore().getState();
    for (const f of fields) store.send("settings_get", scopedBody(f.key, target));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [link, ready, key, fields]);
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-2">
        {scope !== "session" && <Picker label="Project" value={p} options={projects.map((x) => ({ id: x.id, name: x.name || x.id }))} onChange={setProject} />}
        {scope === "role" && <Picker label="Role" value={role} options={roleList(roles).map((r) => ({ id: r.id, name: r.name }))} onChange={setRole} />}
        {scope === "session" && (
          <Picker label="Session" value={session} options={Object.values(sessions).map((s) => ({ id: s.record.id, name: `${s.record.role ?? "agent"} · ${s.record.id.slice(0, 8)}` }))} onChange={setSession} />
        )}
      </div>
      <Served store={scopedStore()} frames="settings_get, settings_set">
        {!ready ? (
          <EmptyState icon={<SlidersHorizontal className="size-6" />} title={`Pick a ${scope} to see its settings`} />
        ) : fields.length === 0 ? (
          <EmptyState icon={<SlidersHorizontal className="size-6" />} title={`No declared setting takes a ${scope} value`} hint="Order: session > role > project > app > default." />
        ) : (
          <PanelCard title={`${fields.length} settings at this ${scope}`} action={<Chip>session &gt; role &gt; project &gt; app</Chip>}>
            <ul className="divide-y">
              {fields.map((f) => (
                <ScopedRow key={f.key} field={f} scope={scope} target={target} />
              ))}
            </ul>
          </PanelCard>
        )}
      </Served>
    </div>
  );
}

export function SettingsPage() {
  const [scope, setScope] = useState<Scope>("app");
  const [query, setQuery] = useState("");
  const tabs = useDeclaredTabs();
  const [tab, setTab] = useState<string | null>(null);
  const current = tab && tabs.includes(tab) ? tab : (tabs[0] ?? null);
  return (
    <TabPage title="Settings" description="Every setting the daemon and its plugins declare, at each scope" actions={<Segmented value={scope} options={SCOPES} onChange={setScope} />}>
      {scope === "app" ? (
        <div className="grid gap-4 sm:grid-cols-[160px_1fr]">
          <nav className="space-y-1">
            <SettingsSearch query={query} onQuery={setQuery} />
            {tabs.map((t) => (
              <Button key={t} variant={t === current && !query ? "secondary" : "ghost"} size="sm" className="w-full justify-start" onClick={() => (setTab(t), setQuery(""))}>
                {t}
              </Button>
            ))}
          </nav>
          <DeclaredSettings tab={current} query={query} />
        </div>
      ) : (
        <ScopedSettings scope={scope} />
      )}
    </TabPage>
  );
}
