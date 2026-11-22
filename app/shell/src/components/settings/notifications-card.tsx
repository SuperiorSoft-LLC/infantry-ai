// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Notifications settings (WAKE-1/2): which notice kinds WAKE a session (spend a turn) and which go quietly to the inbox,
// at the app, a project or a role. The kinds, their descriptions and the effective value come from the daemon (`notify_kinds`);
// this holds no list of kinds. Then the held notices: what the quiet choice kept, with checks run vs turns spent per kind.
import { useEffect, useMemo, useState } from "react";
import { BellOff, CheckCheck } from "lucide-react";
import { heldKind, heldNotices, noticeBody, roleList, turnsSaved, type NoticeKind, type ScopeTarget } from "@infantry/client";
import { Button, Chip, EmptyState, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, ToggleGroup, ToggleGroupItem } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { useAttention } from "@/components/mail/use-mail";
import { useRoles, useRolesLoaded } from "@/components/knowledge/use-knowledge";
import { Segmented } from "@/components/knowledge/parts";
import { Picker, Served } from "@/components/panels/parts";
import { noticeKindsStore, useQuery } from "@/components/panels/use-panels";
import { SettingRow, SettingsCard } from "./setting-card";

type Scope = "app" | "project" | "role";
const SCOPES: { id: Scope; label: string }[] = [
  { id: "app", label: "App" },
  { id: "project", label: "Project" },
  { id: "role", label: "Role" },
];

const ago = (at: number): string => {
  const m = Math.max(0, Math.round((Date.now() - at) / 60_000));
  return m < 1 ? "now" : m < 60 ? `${m}m` : m < 1440 ? `${Math.round(m / 60)}h` : `${Math.round(m / 1440)}d`;
};

function WakeRow({ k, write }: { k: NoticeKind; write: ((kind: string, wake: boolean) => void) | null }) {
  return (
    <SettingRow id={`wake-${k.kind}`} label={k.description || k.kind} hint={`${k.kind}${k.source ? ` · ${k.source}` : ""}`}>
      <ToggleGroup type="single" variant="outline" size="sm" disabled={!write} aria-label={`${k.kind} wakes or goes to the inbox`} value={k.wake ? "wake" : "inbox"} onValueChange={(v) => v && write?.(k.kind, v === "wake")}>
        <ToggleGroupItem value="wake" title="Wake the session: spends a turn">
          Wake
        </ToggleGroupItem>
        <ToggleGroupItem value="inbox" title="Quiet: held in the inbox, no turn">
          Inbox
        </ToggleGroupItem>
      </ToggleGroup>
    </SettingRow>
  );
}

export function NotificationsCard() {
  useRolesLoaded();
  const store = noticeKindsStore();
  const projects = useDaemon((s) => s.projects);
  const roles = useRoles((s) => s.roles);
  const link = useDaemon((s) => s.connection.state);
  const data = useQuery(store, (s) => s.data);
  const loaded = useQuery(store, (s) => s.loaded);
  const [scope, setScope] = useState<Scope>("app");
  const [project, setProject] = useState<string | null>(null);
  const [role, setRole] = useState<string | null>(null);
  const p = project ?? projects[0]?.id ?? null;
  const target: ScopeTarget = useMemo(() => (scope === "app" ? {} : scope === "project" ? { project: p ?? undefined } : { project: p ?? undefined, role: role ?? undefined }), [scope, p, role]);
  const ready = scope === "app" || (scope === "project" && !!p) || (scope === "role" && !!p && !!role);
  const body = noticeBody(target);
  useEffect(() => {
    if (link === "open" && ready) store.getState().refresh(body);
  }, [link, ready, body, store]);
  const write = ready ? (kind: string, wake: boolean) => store.getState().send("notify_kind_set", noticeBody(target, { kind, wake, scope })) : null;
  const here = data.layers.find((l) => l.scope === scope);
  const from = data.from === "default" ? "the built-in default" : `the ${data.from} setting`;
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Segmented value={scope} options={SCOPES} onChange={setScope} />
        {scope !== "app" && <Picker label="Project" value={p} options={projects.map((x) => ({ id: x.id, name: x.name || x.id }))} onChange={setProject} />}
        {scope === "role" && <Picker label="Role" value={role} options={roleList(roles).map((r) => ({ id: r.id, name: r.name }))} onChange={setRole} />}
      </div>
      <Served store={store} frames="notify_kinds, notify_kind_set">
        {!ready ? (
          <EmptyState icon={<BellOff className="size-6" />} title={`Pick a ${scope === "role" ? "project and a role" : "project"} to see its wake rules`} />
        ) : (
          <SettingsCard title="Wake rules" applies={scope === "app" ? "every project and role without its own rule" : scope === "project" ? "this project" : "this role in this project"}>
            <div className="flex flex-wrap items-center gap-2 pb-2 text-xs text-muted-foreground">
              <span>Wake spends a turn; Inbox holds the notice quietly. Effective value comes from {from}.</span>
              <Chip mono className="h-5 text-[10px]">
                {data.setting}
              </Chip>
              {here && scope !== "app" && (
                <Button variant="ghost" size="xs" onClick={() => store.getState().send("settings_set", JSON.stringify({ key: data.setting, ...target, scope, value: null }))}>
                  Clear this {scope} rule
                </Button>
              )}
            </div>
            {data.kinds.length === 0 && <p className="py-2 text-xs text-muted-foreground">{loaded ? "No notice kind is declared." : "Loading…"}</p>}
            {data.kinds.map((k) => (
              <WakeRow key={k.kind} k={k} write={write} />
            ))}
          </SettingsCard>
        )}
      </Served>
      <HeldNotices kinds={data.kinds} />
    </div>
  );
}

/** The held notices (attention items of kind `notice`), filtered by kind and session, and the checks-vs-turns table. */
export function HeldNotices({ kinds }: { kinds: NoticeKind[] }) {
  const items = useAttention((s) => s.items);
  const ensureLoaded = useAttention((s) => s.ensureLoaded);
  const ack = useAttention((s) => s.ack);
  const sessions = useDaemon((s) => s.sessions);
  const [kind, setKind] = useState("all");
  const [session, setSession] = useState("all");
  const [read, setRead] = useState(false);
  useEffect(() => ensureLoaded(), [ensureLoaded]);
  const all = heldNotices(items, { read });
  const kindsHeld = [...new Set(all.map(heldKind))].sort();
  const sessionsHeld = [...new Set(all.map((i) => i.sessionId).filter((s): s is string => !!s))];
  const shown = heldNotices(items, { kind: kind === "all" ? null : kind, session: session === "all" ? null : session, read });
  const nameOf = (id: string | null) => (id ? `${sessions[id]?.record.role ?? "session"} · ${id.slice(0, 8)}` : "no session");
  const counted = kinds.filter((k) => k.checks > 0);
  const saved = counted.reduce((n, k) => n + turnsSaved(k), 0);
  return (
    <div className="space-y-3">
      <SettingsCard title="Checks run vs turns spent" applies="since the daemon started">
        {counted.length === 0 ? (
          <p className="py-1 text-xs text-muted-foreground">No notice has been checked yet.</p>
        ) : (
          <>
            {counted.map((k) => (
              <SettingRow key={k.kind} id={`stat-${k.kind}`} label={k.kind} hint={k.description}>
                <span className="text-xs tabular-nums text-muted-foreground">
                  {k.checks} checked · {k.wakes} turns · {k.inbox} held · {k.duplicates} repeats
                </span>
              </SettingRow>
            ))}
            <p className="pt-2 text-xs font-medium">{saved} turns saved</p>
          </>
        )}
      </SettingsCard>
      <SettingsCard title="Held notices" applies="notices that did not wake a session">
        <div className="flex flex-wrap items-center gap-2 pb-2">
          <FilterSelect label="Kind" value={kind} onChange={setKind} options={kindsHeld.map((k) => ({ id: k, name: k }))} />
          <FilterSelect label="Session" value={session} onChange={setSession} options={sessionsHeld.map((s) => ({ id: s, name: nameOf(s) }))} />
          <Button variant={read ? "secondary" : "outline"} size="xs" onClick={() => setRead(!read)}>
            {read ? "Showing read too" : "Unread only"}
          </Button>
          <Button variant="outline" size="xs" disabled={shown.every((i) => i.ackedAt != null)} onClick={() => shown.filter((i) => i.ackedAt == null).forEach((i) => ack(i.id))}>
            <CheckCheck /> Mark all read
          </Button>
        </div>
        {shown.length === 0 ? (
          <p className="py-1 text-xs text-muted-foreground">Nothing held.</p>
        ) : (
          <ul className="divide-y">
            {shown.map((i) => (
              <li key={i.id} className="flex items-start gap-2 py-2">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-1.5">
                    <Chip mono className="h-5 text-[10px]">
                      {heldKind(i)}
                    </Chip>
                    <span className="text-[11px] text-muted-foreground">
                      {nameOf(i.sessionId)} · {ago(i.at)}
                    </span>
                  </div>
                  {i.detail && <p className="mt-1 line-clamp-2 whitespace-pre-line text-xs text-muted-foreground">{i.detail}</p>}
                </div>
                {i.ackedAt == null && (
                  <Button variant="outline" size="xs" onClick={() => ack(i.id)}>
                    Mark read
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </SettingsCard>
    </div>
  );
}

function FilterSelect({ label, value, options, onChange }: { label: string; value: string; options: { id: string; name: string }[]; onChange: (v: string) => void }) {
  return (
    <Select value={value} onValueChange={onChange}>
      <SelectTrigger aria-label={label} className="h-8 w-48 text-xs">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectItem value="all">All {label.toLowerCase()}s</SelectItem>
        {options.map((o) => (
          <SelectItem key={o.id} value={o.id}>
            {o.name}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}
