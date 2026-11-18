// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The library: skills, the layered skills (global, project or role layer) and the saved prompt library (not the
// pending prompts of stores/prompts.ts). Saves and deletes show at once; the daemon pushes the result to every client
// (`skill_item`, `skills`, `prompt_item`, `prompts`, `layer_skills`); a refusal rolls back and is kept for the view.
import { createStore, type StoreApi } from "zustand/vanilla";
import { Frame, json } from "../wire";
import { requester, type FrameLink } from "./link";

export interface Skill {
  id: string;
  name: string;
  description: string;
  body: string;
  tags: string[];
  createdAt?: number;
  updatedAt?: number;
  attachedOnly?: boolean;
}

export interface SkillSave {
  name: string;
  description: string;
  body: string;
  tags: string[];
  attachedOnly?: boolean;
}

export type SkillLayer = "global" | "project" | "role";

export interface LayerSkill {
  scope: SkillLayer;
  name: string;
  description: string;
  body: string;
  projectId?: string | null;
  role?: string | null;
}

export interface LibraryPrompt {
  id: string;
  name: string;
  body: string;
  tags: string[];
  createdAt?: number;
  updatedAt?: number;
}

export interface LibraryPromptSave {
  name: string;
  body: string;
  tags: string[];
}

export interface LayerTarget {
  scope: SkillLayer;
  projectId?: string | null;
  role?: string | null;
}

export interface LibraryState {
  skills: Record<string, Skill>;
  prompts: Record<string, LibraryPrompt>;
  layer: LayerTarget;
  layerSkills: LayerSkill[];
  loaded: boolean;
  refusal: string | null;
}

export interface LibraryActions {
  ensureLoaded(): void;
  refresh(): void;
  saveSkill(skill: SkillSave): void;
  deleteSkill(id: string): void;
  /** Show the skills of one layer (global, a project, or a role). */
  setLayer(layer: LayerTarget): void;
  saveLayerSkill(name: string, description: string, body: string): void;
  deleteLayerSkill(name: string): void;
  savePrompt(prompt: LibraryPromptSave): void;
  deletePrompt(id: string): void;
  clearRefusal(): void;
  dispose(): void;
}

export type LibraryStore = StoreApi<LibraryState & LibraryActions>;

export function sortedByName<T extends { name: string }>(items: Record<string, T> | T[]): T[] {
  return (Array.isArray(items) ? [...items] : Object.values(items)).sort((a, b) => a.name.localeCompare(b.name));
}

/** Every tag in use, sorted. */
export function tagsOf(items: Record<string, { tags: string[] }>): string[] {
  return [...new Set(Object.values(items).flatMap((i) => i.tags))].sort();
}

const byId = <T extends { id: string }>(list: T[]): Record<string, T> => Object.fromEntries(list.map((t) => [t.id, t]));
/** Skill and prompt ids follow their name until the daemon answers. */
const draftId = (name: string): string => `draft:${name}`;

type Snapshot = Partial<Pick<LibraryState, "skills" | "prompts" | "layerSkills">>;

export function createLibraryStore(link: FrameLink): LibraryStore {
  return createStore<LibraryState & LibraryActions>()((set, get) => {
    const req = requester(link, "lib", (why) => set({ refusal: why }));
    const restore = new Map<string, Snapshot>();
    const send = (type: string, data: string, before?: Snapshot): void => {
      if (req.send(type, data) && before) restore.set(type, before);
      else if (before) set(before);
    };
    const layerFrame = (extra: object = {}): string => {
      const { scope, projectId, role } = get().layer;
      return JSON.stringify({ scope, ...(projectId ? { projectId } : {}), ...(role ? { role } : {}), ...extra });
    };
    const sameLayer = (l: { scope: string; projectId?: string | null; role?: string | null }): boolean => {
      const cur = get().layer;
      return l.scope === cur.scope && (l.projectId ?? null) === (cur.projectId ?? null) && (l.role ?? null) === (cur.role ?? null);
    };
    const dropDraft = <T extends { id: string; name: string }>(map: Record<string, T>, item: T): Record<string, T> => {
      const next = { ...map, [item.id]: item };
      delete next[draftId(item.name)];
      return next;
    };

    const off = link.onFrame((env) => {
      const mine = req.settle(env);
      const done = (type: string): void => void (mine && restore.delete(type));
      switch (env.type) {
        case "skills": {
          const list = json<Skill[]>(env.data);
          if (list) set({ skills: byId(list), loaded: true });
          done("skill_delete");
          return;
        }
        case "skill_item": {
          const s = json<Skill>(env.data);
          if (s?.id) set({ skills: dropDraft(get().skills, s) });
          done("skill_save");
          return;
        }
        case "prompts": {
          const list = json<LibraryPrompt[]>(env.data);
          if (list) set({ prompts: byId(list) });
          done("prompt_delete");
          return;
        }
        case "prompt_item": {
          const p = json<LibraryPrompt>(env.data);
          if (p?.id) set({ prompts: dropDraft(get().prompts, p) });
          done("prompt_save");
          return;
        }
        case "layer_skills": {
          const l = json<{ scope: string; projectId?: string | null; role?: string | null; skills: LayerSkill[] }>(env.data);
          if (l && sameLayer(l)) set({ layerSkills: l.skills ?? [] });
          done("layer_skill_save");
          done("layer_skill_delete");
          return;
        }
        case Frame.ERROR: {
          if (!mine) return;
          const type = env.data.split(" ")[0];
          const before = restore.get(type);
          restore.delete(type);
          set({ ...(before ?? {}), refusal: env.data });
          return;
        }
      }
    });

    return {
      skills: {},
      prompts: {},
      layer: { scope: "global" },
      layerSkills: [],
      loaded: false,
      refusal: null,

      ensureLoaded() {
        if (get().loaded) return;
        req.send("skill_list", "");
        req.send("prompt_list", "");
        req.send("layer_skill_list", layerFrame());
      },
      refresh() {
        set({ loaded: false });
        get().ensureLoaded();
      },
      saveSkill(skill) {
        const { skills } = get();
        const old = Object.values(skills).find((s) => s.name === skill.name);
        const item: Skill = { ...(old ?? { id: draftId(skill.name) }), ...skill };
        set({ skills: { ...skills, [item.id]: item } });
        send("skill_save", JSON.stringify(skill), { skills });
      },
      deleteSkill(id) {
        const { skills } = get();
        const rest = { ...skills };
        delete rest[id];
        set({ skills: rest });
        send("skill_delete", id, { skills });
      },
      setLayer(layer) {
        set({ layer, layerSkills: [] });
        req.send("layer_skill_list", layerFrame());
      },
      saveLayerSkill(name, description, body) {
        const { layerSkills, layer } = get();
        const item: LayerSkill = { scope: layer.scope, name, description, body, projectId: layer.projectId ?? null, role: layer.role ?? null };
        const at = layerSkills.findIndex((s) => s.name === name);
        set({ layerSkills: at < 0 ? [...layerSkills, item] : layerSkills.map((s, i) => (i === at ? item : s)) });
        send("layer_skill_save", layerFrame({ name, description, body }), { layerSkills });
      },
      deleteLayerSkill(name) {
        const { layerSkills } = get();
        set({ layerSkills: layerSkills.filter((s) => s.name !== name) });
        send("layer_skill_delete", layerFrame({ name, description: "" }), { layerSkills });
      },
      savePrompt(prompt) {
        const { prompts } = get();
        const old = Object.values(prompts).find((p) => p.name === prompt.name);
        const item: LibraryPrompt = { ...(old ?? { id: draftId(prompt.name) }), ...prompt };
        set({ prompts: { ...prompts, [item.id]: item } });
        send("prompt_save", JSON.stringify(prompt), { prompts });
      },
      deletePrompt(id) {
        const { prompts } = get();
        const rest = { ...prompts };
        delete rest[id];
        set({ prompts: rest });
        send("prompt_delete", id, { prompts });
      },
      clearRefusal() {
        set({ refusal: null });
      },
      dispose() {
        off();
      },
    };
  });
}
