// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Roles: every role as a card (colour, icon, name, source, plugin/disabled), an edit dialog (persona, description,
// colour, icon, ordered traits, the model pin) and the trait library as a second view. Editing goes through the one
// modal.
import { useMemo, useState } from "react";
import { ArrowDown, ArrowUp, Plus, RefreshCw, Sparkle, Trash2, UserCog, X } from "lucide-react";
import { MAX_ROLE_TRAITS, roleList, type Role, type Trait } from "@infantry/client";
import { AppDialog, Button, Chip, EmptyState, Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Textarea } from "@infantry/ui";
import { TabPage } from "../tabs/tab-page";
import { ROLE_ICON_NAMES, RoleGlyph } from "../agents/role-icon";
import { Field, RefusalBanner, Segmented } from "../knowledge/parts";
import { rolesStore, useRoles, useRolesLoaded } from "../knowledge/use-knowledge";
import { useUsage } from "../usage/use-usage";

type View = "roles" | "traits";

interface RoleDraft {
  isNew: boolean;
  id: string;
  name: string;
  color: string;
  icon: string;
  description: string;
  persona: string;
  traitIds: string[];
  provider: string;
  model: string;
  effort: string;
}

interface TraitDraft {
  isNew: boolean;
  id: string;
  name: string;
  body: string;
}

type Modal = { kind: "role"; draft: RoleDraft } | { kind: "trait"; draft: TraitDraft };

const PICK = "__pick__";

function sourceTone(r: Role): "neutral" | "blue" | "amber" {
  return r.source === "BUILT_IN" ? "neutral" : r.source === "CUSTOM" ? "blue" : "amber";
}

export function RolesTab() {
  useRolesLoaded();
  const roles = useRoles((s) => s.roles);
  const traits = useRoles((s) => s.traits);
  const bindings = useRoles((s) => s.bindings);
  const roleTraits = useRoles((s) => s.roleTraits);
  const refusal = useRoles((s) => s.refusal);
  const { refresh, clearRefusal } = useRoles((s) => s);
  const [view, setView] = useState<View>("roles");
  const [modal, setModal] = useState<Modal | null>(null);

  const list = useMemo(() => roleList(roles), [roles]);
  const traitList = useMemo(() => Object.values(traits).sort((a, b) => a.name.localeCompare(b.name)), [traits]);

  const editRole = (r: Role | null): void => {
    const b = r ? bindings[r.id] : undefined;
    setModal({
      kind: "role",
      draft: {
        isNew: !r,
        id: r?.id ?? "",
        name: r?.name ?? "",
        color: r?.color || "#5b8def",
        icon: r?.icon || "assistant",
        description: r?.description ?? "",
        persona: r?.persona ?? "",
        traitIds: r ? (roleTraits[r.id] ?? []) : [],
        provider: b?.provider ?? "",
        model: b?.model ?? "",
        effort: b?.effort ?? "",
      },
    });
  };
  const editTrait = (t: Trait | null): void => setModal({ kind: "trait", draft: { isNew: !t, id: t?.id ?? "", name: t?.name ?? "", body: t?.body ?? "" } });

  return (
    <TabPage
      title="Roles"
      description="Role text holds preferences; product code holds mechanisms"
      actions={
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={refresh} aria-label="Refresh">
            <RefreshCw />
          </Button>
          <Button variant="outline" size="sm" onClick={() => (view === "roles" ? editRole(null) : editTrait(null))}>
            <Plus /> {view === "roles" ? "New role" : "New trait"}
          </Button>
        </div>
      }
    >
      <RefusalBanner text={refusal} onDismiss={clearRefusal} />
      <Segmented
        value={view}
        options={[
          { id: "roles", label: `Roles (${list.length})` },
          { id: "traits", label: `Traits (${traitList.length})` },
        ]}
        onChange={setView}
        className="mb-3 max-w-xs"
      />
      {view === "roles" ? (
        list.length === 0 ? (
          <EmptyState icon={<UserCog className="size-6" />} title="No roles yet" />
        ) : (
          <div className="grid grid-cols-[repeat(auto-fill,minmax(240px,1fr))] gap-3">
            {list.map((r) => {
              const b = bindings[r.id];
              const n = roleTraits[r.id]?.length ?? 0;
              return (
                <button key={r.id} type="button" onClick={() => editRole(r)} className="flex items-center gap-3 rounded-lg border bg-card p-3 text-left transition-colors hover:border-info/50">
                  <div className="relative grid size-9 shrink-0 place-items-center rounded-md border bg-raised" style={{ color: r.color || undefined }}>
                    <RoleGlyph icon={r.icon} className="size-4" />
                    <span className="absolute -top-1 -right-1 size-2.5 rounded-full border border-background" style={{ background: r.color || "var(--muted-foreground)" }} />
                  </div>
                  <div className="min-w-0 flex-1">
                    <h3 className="truncate font-medium">{r.name}</h3>
                    <p className="truncate text-xs text-muted-foreground">{b?.model ? `${b.provider ? `${b.provider} · ` : ""}${b.model}` : "Project default model"}</p>
                    <div className="mt-1.5 flex flex-wrap gap-1">
                      <Chip tone={sourceTone(r)} className="h-5 text-[10px]">
                        {r.source}
                      </Chip>
                      {r.plugin && <Chip className="h-5 text-[10px]">{r.plugin}</Chip>}
                      {r.disabled && (
                        <Chip tone="red" className="h-5 text-[10px]">
                          disabled
                        </Chip>
                      )}
                      {n > 0 && <Chip className="h-5 text-[10px]">{n} traits</Chip>}
                    </div>
                  </div>
                </button>
              );
            })}
          </div>
        )
      ) : traitList.length === 0 ? (
        <EmptyState icon={<Sparkle className="size-6" />} title="No traits yet" hint="A trait is a reusable piece of role text." />
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(260px,1fr))] gap-3">
          {traitList.map((t) => (
            <button key={t.id} type="button" onClick={() => editTrait(t)} className="rounded-lg border bg-card p-3 text-left transition-colors hover:border-info/50">
              <div className="flex items-center gap-2">
                <span className="min-w-0 flex-1 truncate font-medium">{t.name}</span>
                <Chip mono className="h-5 text-[10px]">
                  {t.id}
                </Chip>
                {t.version !== undefined && <Chip className="h-5 text-[10px]">v{t.version}</Chip>}
              </div>
              <p className="mt-1 line-clamp-3 text-xs text-muted-foreground">{t.body}</p>
              {t.check && (
                <Chip tone="blue" className="mt-1.5 h-5 text-[10px]">
                  reports to {t.check.reportTo}
                </Chip>
              )}
            </button>
          ))}
        </div>
      )}
      <RolesDialog modal={modal} setModal={setModal} traits={traitList} roles={roles} />
    </TabPage>
  );
}

function RolesDialog({ modal, setModal, traits, roles }: { modal: Modal | null; setModal: (m: Modal | null) => void; traits: Trait[]; roles: Record<string, Role> }) {
  const limits = useUsage((s) => s.limits);
  const providers = useMemo(() => limits.map((l) => l.provider), [limits]);
  const bindings = useRoles((s) => s.bindings);
  const close = (): void => setModal(null);
  const store = rolesStore().getState;

  if (modal?.kind === "trait") {
    const d = modal.draft;
    const set = (patch: Partial<TraitDraft>): void => setModal({ kind: "trait", draft: { ...d, ...patch } });
    return (
      <AppDialog
        open
        onOpenChange={(o) => !o && close()}
        title={d.isNew ? "New trait" : d.name}
        description="Trait text is appended to every role that lists it, in the role's order."
        wide
        footer={
          <div className="flex w-full justify-between gap-2">
            {!d.isNew ? (
              <Button
                variant="destructive"
                size="sm"
                onClick={() => {
                  store().deleteTrait(d.id);
                  close();
                }}
              >
                <Trash2 /> Delete
              </Button>
            ) : (
              <span />
            )}
            <Button
              size="sm"
              disabled={!d.id.trim() || !d.name.trim() || !d.body.trim()}
              onClick={() => {
                store().saveTrait({ id: d.id.trim(), name: d.name.trim(), body: d.body });
                close();
              }}
            >
              Save
            </Button>
          </div>
        }
      >
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field id="trait-id" label="Id">
              <Input id="trait-id" className="font-mono text-xs" value={d.id} disabled={!d.isNew} onChange={(e) => set({ id: e.target.value })} />
            </Field>
            <Field id="trait-name" label="Name">
              <Input id="trait-name" value={d.name} onChange={(e) => set({ name: e.target.value })} />
            </Field>
          </div>
          <Field id="trait-body" label="Text">
            <Textarea id="trait-body" rows={10} className="font-mono text-xs" value={d.body} onChange={(e) => set({ body: e.target.value })} />
          </Field>
        </div>
      </AppDialog>
    );
  }

  const d = modal?.kind === "role" ? modal.draft : null;
  const set = (patch: Partial<RoleDraft>): void => {
    if (d) setModal({ kind: "role", draft: { ...d, ...patch } });
  };
  const role = d && !d.isNew ? roles[d.id] : undefined;
  const providerOptions = [...new Set([...providers, ...Object.values(bindings).map((b) => b.provider ?? "")].filter(Boolean))].sort();
  const modelOptions = [...new Set(Object.values(bindings).filter((b) => !d?.provider || b.provider === d.provider).map((b) => b.model ?? "").filter(Boolean))].sort();
  const move = (i: number, by: number): void => {
    if (!d) return;
    const ids = [...d.traitIds];
    const j = i + by;
    if (j < 0 || j >= ids.length) return;
    [ids[i], ids[j]] = [ids[j], ids[i]];
    set({ traitIds: ids });
  };

  const save = (): void => {
    if (!d) return;
    const s = store();
    const id = d.id.trim();
    s.saveRole({ id, name: d.name.trim(), color: d.color, icon: d.icon, description: d.description, persona: d.persona });
    const before = s.roleTraits[id] ?? [];
    if (before.join("\n") !== d.traitIds.join("\n")) s.setRoleTraits(id, d.traitIds);
    const b = s.bindings[id];
    if ((b?.provider ?? "") !== d.provider || (b?.model ?? "") !== d.model || (b?.effort ?? "") !== d.effort) {
      s.setBinding({ roleId: id, provider: d.provider.trim(), model: d.model.trim(), effort: d.effort.trim() });
    }
    close();
  };

  return (
    <AppDialog
      open={d !== null}
      onOpenChange={(o) => !o && close()}
      title={d?.isNew ? "New role" : (d?.name ?? "")}
      description={role ? `${role.source}${role.edited ? " · edited" : ""}${role.plugin ? ` · from ${role.plugin}` : ""}` : undefined}
      wide
      footer={
        <div className="flex w-full justify-between gap-2">
          {role ? (
            <Button
              variant="destructive"
              size="sm"
              onClick={() => {
                store().deleteRole(role.id);
                close();
              }}
            >
              <Trash2 /> {role.source === "BUILT_IN" ? "Reset to built-in" : "Delete"}
            </Button>
          ) : (
            <span />
          )}
          <Button size="sm" disabled={!d?.id.trim() || !d?.name.trim()} onClick={save}>
            Save
          </Button>
        </div>
      }
    >
      {d && (
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2">
            <Field id="role-id" label="Id">
              <Input id="role-id" className="font-mono text-xs" value={d.id} disabled={!d.isNew} onChange={(e) => set({ id: e.target.value })} />
            </Field>
            <Field id="role-name" label="Name">
              <Input id="role-name" value={d.name} onChange={(e) => set({ name: e.target.value })} />
            </Field>
            <Field id="role-color" label="Colour">
              <div className="flex gap-2">
                <input type="color" aria-label="Pick colour" value={/^#[0-9a-f]{6}$/i.test(d.color) ? d.color : "#5b8def"} onChange={(e) => set({ color: e.target.value })} className="h-9 w-10 cursor-pointer rounded-md border bg-transparent" />
                <Input id="role-color" className="font-mono text-xs" value={d.color} onChange={(e) => set({ color: e.target.value })} />
              </div>
            </Field>
            <Field id="role-icon" label="Icon">
              <Select value={d.icon} onValueChange={(v) => set({ icon: v })}>
                <SelectTrigger id="role-icon" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {[...new Set([...ROLE_ICON_NAMES, d.icon])].map((n) => (
                    <SelectItem key={n} value={n}>
                      <RoleGlyph icon={n} className="size-3.5" /> {n}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
          </div>
          <Field id="role-desc" label="Description">
            <Input id="role-desc" value={d.description} onChange={(e) => set({ description: e.target.value })} />
          </Field>
          <Field id="role-persona" label="Persona" hint="The role's own text; traits are added after it.">
            <Textarea id="role-persona" rows={8} className="font-mono text-xs" value={d.persona} onChange={(e) => set({ persona: e.target.value })} />
          </Field>

          <section className="space-y-2 rounded-lg border p-3">
            <div className="flex items-center justify-between gap-2">
              <h4 className="text-sm font-medium">Traits</h4>
              <span className="text-[11px] text-muted-foreground">
                {d.traitIds.length} / {MAX_ROLE_TRAITS}
              </span>
            </div>
            {d.traitIds.length === 0 ? (
              <p className="text-xs text-muted-foreground">No traits.</p>
            ) : (
              <ol className="space-y-1">
                {d.traitIds.map((id, i) => (
                  <li key={id} className="flex items-center gap-2 rounded-md border bg-raised px-2 py-1 text-xs">
                    <span className="w-4 text-muted-foreground">{i + 1}</span>
                    <span className="min-w-0 flex-1 truncate">{traits.find((t) => t.id === id)?.name ?? id}</span>
                    <Button variant="ghost" size="icon-xs" aria-label="Move up" disabled={i === 0} onClick={() => move(i, -1)}>
                      <ArrowUp />
                    </Button>
                    <Button variant="ghost" size="icon-xs" aria-label="Move down" disabled={i === d.traitIds.length - 1} onClick={() => move(i, 1)}>
                      <ArrowDown />
                    </Button>
                    <Button variant="ghost" size="icon-xs" aria-label="Remove" onClick={() => set({ traitIds: d.traitIds.filter((x) => x !== id) })}>
                      <X />
                    </Button>
                  </li>
                ))}
              </ol>
            )}
            <Select value={PICK} onValueChange={(v) => v !== PICK && set({ traitIds: [...d.traitIds, v] })} disabled={d.traitIds.length >= MAX_ROLE_TRAITS}>
              <SelectTrigger aria-label="Add trait" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={PICK}>Add a trait...</SelectItem>
                {traits
                  .filter((t) => !d.traitIds.includes(t.id))
                  .map((t) => (
                    <SelectItem key={t.id} value={t.id}>
                      {t.name}
                    </SelectItem>
                  ))}
              </SelectContent>
            </Select>
          </section>

          <section className="space-y-2 rounded-lg border p-3">
            <h4 className="text-sm font-medium">Model pin</h4>
            <p className="text-[11px] text-muted-foreground">Empty fields use the project default.</p>
            <div className="grid gap-3 sm:grid-cols-3">
              <Field id="role-provider" label="Provider">
                <Input id="role-provider" list="role-provider-options" value={d.provider} onChange={(e) => set({ provider: e.target.value })} placeholder="default" />
                <datalist id="role-provider-options">
                  {providerOptions.map((p) => (
                    <option key={p} value={p} />
                  ))}
                </datalist>
              </Field>
              <Field id="role-model" label="Model">
                <Input id="role-model" list="role-model-options" value={d.model} onChange={(e) => set({ model: e.target.value })} placeholder="default" />
                <datalist id="role-model-options">
                  {modelOptions.map((m) => (
                    <option key={m} value={m} />
                  ))}
                </datalist>
              </Field>
              <Field id="role-effort" label="Effort">
                <Input id="role-effort" list="role-effort-options" value={d.effort} onChange={(e) => set({ effort: e.target.value })} placeholder="default" />
                <datalist id="role-effort-options">
                  {["low", "medium", "high", "xhigh", "max"].map((m) => (
                    <option key={m} value={m} />
                  ))}
                </datalist>
              </Field>
            </div>
          </section>
        </div>
      )}
    </AppDialog>
  );
}
