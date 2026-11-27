// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Epics, Profiles and Pods (RUST-UI-7): the daemon's own data in place of the mocks.
//   Epics    `epic_list` for the picked project; "New epic" sends `epic_save` with a name (the only thing typed).
//   Profiles the saved agent profiles the daemon store reads on open (`agent_list`).
//   Pods     the agent registry a pod is built from (`agent_registry`, enable/disable), then the pods this client knows
//            (`pod_query`; the daemon has no pod-list frame).
import { useMemo, useState } from "react";
import { Box, IdCard, Layers, Plus } from "lucide-react";
import { byContributor, reviewQueue, type AgentProfile } from "@infantry/client";
import { Button, Chip, EmptyState, Input, PanelCard, Switch } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { TabPage } from "@/components/tabs/tab-page";
import { ProjectPicker, RefreshButton, Served } from "./parts";
import { EpicArchiveButton } from "@/components/board/archive-dialog";
import { epicsStore, podsStore, registryStore, useQuery, useQueryOnOpen } from "./use-panels";

/** The picked project, defaulting to the daemon's first. */
function useProjectChoice(): [string | null, (id: string) => void] {
  const projects = useDaemon((s) => s.projects);
  const [picked, setPicked] = useState<string | null>(null);
  const id = picked && projects.some((p) => p.id === picked) ? picked : (projects[0]?.id ?? null);
  return [id, setPicked];
}

export function EpicsTab() {
  const [project, setProject] = useProjectChoice();
  const store = epicsStore();
  useQueryOnOpen(store, project ?? undefined, !project);
  const epics = useQuery(store, (s) => s.data);
  const loaded = useQuery(store, (s) => s.loaded);
  const [name, setName] = useState("");
  const shown = epics.filter((e) => e.projectId === project);
  const create = () => {
    if (!project || !name.trim()) return;
    store.getState().send("epic_save", JSON.stringify({ projectId: project, name: name.trim(), goal: "" }));
    setName("");
  };
  return (
    <TabPage
      title="Epics"
      description="Groups of tickets, each with its own base branch"
      actions={
        <>
          <ProjectPicker value={project} onChange={setProject} />
          <RefreshButton store={store} body={project ?? undefined} />
        </>
      }
    >
      <Served store={store} frames="epic_list, epic_save">
        {!project ? (
          <EmptyState icon={<Layers className="size-6" />} title="No project yet" hint="Epics belong to a project; add one first." />
        ) : (
          <div className="space-y-3">
            <form
              className="flex gap-2"
              onSubmit={(e) => {
                e.preventDefault();
                create();
              }}
            >
              <Input aria-label="Epic name" placeholder="New epic name" className="h-8 max-w-xs text-xs" value={name} onChange={(e) => setName(e.target.value)} />
              <Button type="submit" size="sm" disabled={!name.trim()}>
                <Plus /> New epic
              </Button>
            </form>
            {shown.length === 0 ? (
              <EmptyState icon={<Layers className="size-6" />} title={loaded ? "No epics in this project" : "Reading epics…"} />
            ) : (
              <div className="grid gap-3 lg:grid-cols-2">
                {shown.map((e) => (
                  <PanelCard key={e.id} title={e.name} action={<span className="flex items-center gap-1"><EpicArchiveButton epicId={e.id} projectId={project} /><Chip mono>{`${e.storyIds?.length ?? 0} tickets`}</Chip></span>}>
                    {e.goal && <p className="text-[13px]">{e.goal}</p>}
                    <p className="mt-1 font-mono text-xs text-muted-foreground">{e.baseBranch ? `base ${e.baseBranch}` : "no base branch yet"}</p>
                  </PanelCard>
                ))}
              </div>
            )}
          </div>
        )}
      </Served>
    </TabPage>
  );
}

export function ProfilesTab() {
  const agents = useDaemon((s) => s.agents);
  const projects = useDaemon((s) => s.projects);
  const link = useDaemon((s) => s.connection.state);
  const groups = useMemo(() => {
    const by = new Map<string, AgentProfile[]>();
    for (const a of Object.values(agents)) {
      const k = a.projectId ?? "";
      by.set(k, [...(by.get(k) ?? []), a]);
    }
    return [...by.entries()].map(([pid, list]) => ({ title: pid ? (projects.find((p) => p.id === pid)?.name ?? pid) : "Every project", list: list.sort((a, b) => a.name.localeCompare(b.name)) }));
  }, [agents, projects]);
  return (
    <TabPage title="Profiles" description="Saved agents: a name, a role, a harness and a model">
      {groups.length === 0 ? (
        <EmptyState icon={<IdCard className="size-6" />} title={link === "open" ? "No saved profiles" : "Waiting for the daemon…"} hint="A profile is saved when an agent is created with a name." />
      ) : (
        <div className="space-y-3">
          {groups.map((g) => (
            <PanelCard key={g.title} title={g.title}>
              <ul className="divide-y">
                {g.list.map((a) => (
                  <li key={a.id ?? a.name} className="flex items-center gap-2 py-1.5 text-[13px]">
                    <span className="min-w-0 flex-1 truncate font-medium">{a.name}</span>
                    <Chip tone="blue">{a.role}</Chip>
                    {a.provider && <Chip mono>{a.provider}</Chip>}
                    {a.model && <Chip mono>{a.model}</Chip>}
                  </li>
                ))}
              </ul>
            </PanelCard>
          ))}
        </div>
      )}
    </TabPage>
  );
}

export function PodsTab() {
  const registry = registryStore();
  const pods = podsStore();
  useQueryOnOpen(registry);
  // A pod_query with no id: a daemon that serves pods refuses it for want of an id; one that does not says so.
  useQueryOnOpen(pods, "");
  const agents = useQuery(registry, (s) => s.data);
  const loaded = useQuery(registry, (s) => s.loaded);
  const known = useQuery(pods, (s) => s.data);
  const groups = useMemo(() => byContributor(agents), [agents]);
  const toggle = (id: string, on: boolean) => registry.getState().send("agent_registry", JSON.stringify({ op: on ? "enable" : "disable", id }));
  return (
    <TabPage title="Pods" description="Groups of agents working one goal, and the agents they are built from" actions={<RefreshButton store={registry} />}>
      <div className="space-y-3">
        <Served store={registry} frames="agent_registry">
          {groups.length === 0 ? (
            <EmptyState icon={<Box className="size-6" />} title={loaded ? "No agents registered" : "Reading the agent registry…"} />
          ) : (
            groups.map((g) => (
              <PanelCard key={g.contributor} title={g.contributor === "roles" ? "Roles" : g.contributor} action={<Chip mono>{g.agents.length}</Chip>}>
                <ul className="divide-y">
                  {g.agents.map((a) => (
                    <li key={a.definition.id} className="flex items-center gap-2 py-1.5 text-[13px]">
                      <span className="min-w-0 flex-1 truncate">
                        <span className="font-medium">{a.definition.name ?? a.definition.id}</span>
                        <span className="ml-2 font-mono text-xs text-muted-foreground">{a.definition.id}</span>
                      </span>
                      {a.definition.role && a.definition.role !== a.definition.id && <Chip tone="blue">{a.definition.role}</Chip>}
                      <Switch aria-label={`${a.enabled ? "Disable" : "Enable"} ${a.definition.id}`} checked={a.enabled} onCheckedChange={(on) => toggle(a.definition.id, on)} />
                    </li>
                  ))}
                </ul>
              </PanelCard>
            ))
          )}
        </Served>
        <PanelCard title="Pods">
          <Served store={pods} frames="pod_query, pod_submit, pod_cancel (and a pod list)">
            {Object.keys(known).length === 0 ? (
              <p className="text-xs text-muted-foreground">No pods known to this window. The daemon has no pod-list frame.</p>
            ) : (
              <ul className="space-y-1 text-[13px]">
                {Object.values(known).map((p) => (
                  <li key={p.id} className="flex gap-2">
                    <span className="min-w-0 flex-1 truncate">{p.goal || p.id}</span>
                    <Chip>{p.state ?? "?"}</Chip>
                    <Chip mono>{`${reviewQueue({ [p.id]: p }).length} in review`}</Chip>
                  </li>
                ))}
              </ul>
            )}
          </Served>
        </PanelCard>
      </div>
    </TabPage>
  );
}
