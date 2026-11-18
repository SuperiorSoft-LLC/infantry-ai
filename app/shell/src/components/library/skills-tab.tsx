// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Skills: the skill library, and the layered skills of one layer (global, the current project, or a role). Editing goes
// through the one modal.
import { useEffect, useMemo, useState } from "react";
import { Plus, RefreshCw, Sparkles, Trash2 } from "lucide-react";
import { roleList, sortedByName, type LayerSkill, type Skill, type SkillLayer } from "@infantry/client";
import { AppDialog, Button, Chip, EmptyState, Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Switch, Textarea } from "@infantry/ui";
import { TabPage } from "../tabs/tab-page";
import { Field, RefusalBanner, Segmented, splitTags } from "../knowledge/parts";
import { libraryStore, useLibrary, useLibraryLoaded, useRoles, useRolesLoaded } from "../knowledge/use-knowledge";
import { useProjects, useProjectsLoaded } from "../git/use-projects";

type View = "library" | "layers";

type Modal =
  | { kind: "skill"; isNew: boolean; id: string; name: string; description: string; body: string; tags: string; attachedOnly: boolean }
  | { kind: "layer"; isNew: boolean; name: string; description: string; body: string };

const LAYERS: { id: SkillLayer; label: string }[] = [
  { id: "global", label: "Global" },
  { id: "project", label: "Project" },
  { id: "role", label: "Role" },
];

export function SkillsTab() {
  useLibraryLoaded();
  useRolesLoaded();
  useProjectsLoaded();
  const skills = useLibrary((s) => s.skills);
  const layer = useLibrary((s) => s.layer);
  const layerSkills = useLibrary((s) => s.layerSkills);
  const refusal = useLibrary((s) => s.refusal);
  const { refresh, clearRefusal, setLayer } = useLibrary((s) => s);
  const roles = useRoles((s) => s.roles);
  const projectId = useProjects((s) => s.currentId);
  const [view, setView] = useState<View>("library");
  const [modal, setModal] = useState<Modal | null>(null);

  const list = useMemo(() => sortedByName(skills), [skills]);
  const layered = useMemo(() => sortedByName(layerSkills), [layerSkills]);
  const roleIds = useMemo(() => roleList(roles).map((r) => r.id), [roles]);

  // The project layer follows the current project.
  useEffect(() => {
    if (layer.scope === "project" && layer.projectId !== projectId) setLayer({ scope: "project", projectId });
  }, [layer, projectId, setLayer]);

  const pickLayer = (scope: SkillLayer): void => {
    if (scope === "global") setLayer({ scope });
    else if (scope === "project") setLayer({ scope, projectId });
    else setLayer({ scope, role: layer.role ?? roleIds[0] ?? null });
  };

  const editSkill = (s: Skill | null): void =>
    setModal({ kind: "skill", isNew: !s, id: s?.id ?? "", name: s?.name ?? "", description: s?.description ?? "", body: s?.body ?? "", tags: s?.tags.join(", ") ?? "", attachedOnly: s?.attachedOnly ?? false });
  const editLayer = (s: LayerSkill | null): void => setModal({ kind: "layer", isNew: !s, name: s?.name ?? "", description: s?.description ?? "", body: s?.body ?? "" });

  return (
    <TabPage
      title="Skills"
      description="Reusable instructions agents load when a task needs them"
      actions={
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={refresh} aria-label="Refresh">
            <RefreshCw />
          </Button>
          <Button variant="outline" size="sm" onClick={() => (view === "library" ? editSkill(null) : editLayer(null))} disabled={view === "layers" && layer.scope === "project" && !projectId}>
            <Plus /> New skill
          </Button>
        </div>
      }
    >
      <RefusalBanner text={refusal} onDismiss={clearRefusal} />
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <Segmented
          value={view}
          options={[
            { id: "library", label: `Library (${list.length})` },
            { id: "layers", label: "Layers" },
          ]}
          onChange={setView}
          className="w-56"
        />
        {view === "layers" && (
          <>
            <Segmented value={layer.scope} options={LAYERS} onChange={pickLayer} className="w-60" />
            {layer.scope === "role" && (
              <Select value={layer.role ?? ""} onValueChange={(role) => setLayer({ scope: "role", role })}>
                <SelectTrigger aria-label="Role" className="h-8 w-44">
                  <SelectValue placeholder="Pick a role" />
                </SelectTrigger>
                <SelectContent>
                  {roleIds.map((id) => (
                    <SelectItem key={id} value={id}>
                      {roles[id]?.name ?? id}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            )}
          </>
        )}
      </div>
      {view === "library" ? (
        list.length === 0 ? (
          <EmptyState icon={<Sparkles className="size-6" />} title="No skills yet" />
        ) : (
          <div className="grid grid-cols-[repeat(auto-fill,minmax(260px,1fr))] gap-3">
            {list.map((s) => (
              <button key={s.id} type="button" onClick={() => editSkill(s)} className="rounded-lg border bg-card p-3 text-left transition-colors hover:border-info/50">
                <div className="flex items-center gap-2">
                  <span className="min-w-0 flex-1 truncate font-medium">{s.name}</span>
                  {s.attachedOnly && (
                    <Chip tone="amber" className="h-5 text-[10px]">
                      attached only
                    </Chip>
                  )}
                </div>
                <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{s.description}</p>
                {s.tags.length > 0 && (
                  <div className="mt-1.5 flex flex-wrap gap-1">
                    {s.tags.map((t) => (
                      <Chip key={t} className="h-5 text-[10px]">
                        {t}
                      </Chip>
                    ))}
                  </div>
                )}
              </button>
            ))}
          </div>
        )
      ) : layered.length === 0 ? (
        <EmptyState icon={<Sparkles className="size-6" />} title="No skills in this layer" hint="A layer skill applies to every agent of its scope." />
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(260px,1fr))] gap-3">
          {layered.map((s) => (
            <button key={s.name} type="button" onClick={() => editLayer(s)} className="rounded-lg border bg-card p-3 text-left transition-colors hover:border-info/50">
              <div className="flex items-center gap-2">
                <span className="min-w-0 flex-1 truncate font-medium">{s.name}</span>
                <Chip tone="blue" className="h-5 text-[10px]">
                  {s.scope}
                </Chip>
              </div>
              <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{s.description}</p>
            </button>
          ))}
        </div>
      )}
      <SkillDialog modal={modal} setModal={setModal} />
    </TabPage>
  );
}

function SkillDialog({ modal, setModal }: { modal: Modal | null; setModal: (m: Modal | null) => void }) {
  const close = (): void => setModal(null);
  const lib = libraryStore().getState;
  if (!modal) return null;
  const m = modal;
  const canSave = !!m.name.trim() && !!m.body.trim();
  const save = (): void => {
    if (m.kind === "skill") lib().saveSkill({ name: m.name.trim(), description: m.description.trim(), body: m.body, tags: splitTags(m.tags), attachedOnly: m.attachedOnly });
    else lib().saveLayerSkill(m.name.trim(), m.description.trim(), m.body);
    close();
  };
  const remove = (): void => {
    if (m.kind === "skill") lib().deleteSkill(m.id);
    else lib().deleteLayerSkill(m.name);
    close();
  };
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && close()}
      title={m.isNew ? "New skill" : m.name}
      description={m.kind === "layer" ? "Saved in the selected layer." : undefined}
      wide
      footer={
        <div className="flex w-full justify-between gap-2">
          {!m.isNew ? (
            <Button variant="destructive" size="sm" onClick={remove}>
              <Trash2 /> Delete
            </Button>
          ) : (
            <span />
          )}
          <Button size="sm" disabled={!canSave} onClick={save}>
            Save
          </Button>
        </div>
      }
    >
      <div className="space-y-3">
        <Field id="skill-name" label="Name">
          <Input id="skill-name" value={m.name} disabled={!m.isNew} onChange={(e) => setModal({ ...m, name: e.target.value })} />
        </Field>
        <Field id="skill-desc" label="Description" hint="Agents pick a skill by this line.">
          <Input id="skill-desc" value={m.description} onChange={(e) => setModal({ ...m, description: e.target.value })} />
        </Field>
        {m.kind === "skill" && (
          <div className="grid items-end gap-3 sm:grid-cols-[1fr_auto]">
            <Field id="skill-tags" label="Tags" hint="Comma separated.">
              <Input id="skill-tags" value={m.tags} onChange={(e) => setModal({ ...m, tags: e.target.value })} />
            </Field>
            <label className="flex items-center gap-2 pb-6 text-xs">
              <Switch checked={m.attachedOnly} onCheckedChange={(v) => setModal({ ...m, attachedOnly: v })} /> Attached only
            </label>
          </div>
        )}
        <Field id="skill-body" label="Body">
          <Textarea id="skill-body" rows={12} className="font-mono text-xs" value={m.body} onChange={(e) => setModal({ ...m, body: e.target.value })} />
        </Field>
      </div>
    </AppDialog>
  );
}
