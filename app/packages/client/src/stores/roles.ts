// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Roles, their model pins (bindings), the trait library and each role's ordered traits, fed by the daemon's frames.
// A save shows at once; the daemon pushes the saved item to every client, and a refusal rolls back and is kept for
// the view.
import { createStore, type StoreApi } from "zustand/vanilla";
import { Frame, json } from "../wire";
import { requester, type FrameLink } from "./link";

/** At most this many traits per role (the daemon refuses more). */
export const MAX_ROLE_TRAITS = 8;

export interface RoleAvatar {
  tier: "override" | "bundled" | "generated";
  url?: string | null;
  mediaType?: string | null;
  color: string;
  initials: string;
  version?: string | null;
}

export interface Role {
  id: string;
  name: string;
  color: string;
  persona: string;
  source: "BUILT_IN" | "CUSTOM" | "GENERATED";
  icon: string;
  description: string;
  responseDetail?: string;
  plugin?: string | null;
  disabled?: boolean;
  edited?: boolean;
  cards?: string[];
  tools?: string[];
  mcpServers?: string[];
  mcpPurpose?: string;
  avatar?: RoleAvatar;
}

export interface RoleSave {
  id: string;
  name: string;
  color?: string;
  persona: string;
  icon?: string;
  description?: string;
}

export interface RoleBinding {
  roleId: string;
  provider?: string | null;
  model?: string | null;
  effort?: string | null;
  updatedAt?: number;
}

export interface RoleTraits {
  roleId: string;
  traitIds: string[];
  updatedAt?: number;
}

export interface TraitCheck {
  kind: "report";
  reportTo: string;
  fields: string[];
  reprompt: boolean;
}

export interface Trait {
  id: string;
  name: string;
  version?: number;
  body: string;
  check?: TraitCheck | null;
  createdAt?: number;
  updatedAt?: number;
}

export interface TraitSave {
  id: string;
  name: string;
  body: string;
  check?: TraitCheck | null;
}

export interface RolesState {
  roles: Record<string, Role>;
  bindings: Record<string, RoleBinding>;
  roleTraits: Record<string, string[]>;
  traits: Record<string, Trait>;
  loaded: boolean;
  refusal: string | null;
}

export interface RolesActions {
  ensureLoaded(): void;
  refresh(): void;
  saveRole(role: RoleSave): void;
  /** Deletes a custom role, or resets a built-in role's override. */
  deleteRole(id: string): void;
  /** Pin a role's provider/model/effort; empty values clear the pin. */
  setBinding(binding: RoleBinding): void;
  /** The role's ordered traits (at most [MAX_ROLE_TRAITS]). */
  setRoleTraits(roleId: string, traitIds: string[]): void;
  saveTrait(trait: TraitSave): void;
  deleteTrait(id: string): void;
  clearRefusal(): void;
  dispose(): void;
}

export type RolesStore = StoreApi<RolesState & RolesActions>;

/** Roles in list order: built-ins first, then by name. */
export function roleList(roles: Record<string, Role>): Role[] {
  const rank = (r: Role): number => (r.source === "BUILT_IN" ? 0 : 1);
  return Object.values(roles).sort((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name));
}

const byKey = <T>(list: T[], key: (t: T) => string): Record<string, T> => Object.fromEntries(list.map((t) => [key(t), t]));

type Snapshot = Partial<Pick<RolesState, "roles" | "bindings" | "roleTraits" | "traits">>;

export function createRolesStore(link: FrameLink): RolesStore {
  return createStore<RolesState & RolesActions>()((set, get) => {
    const req = requester(link, "roles", (why) => set({ refusal: why }));
    /** What to restore per request type when the daemon refuses it. */
    const restore = new Map<string, Snapshot>();
    const send = (type: string, data: string, before?: Snapshot): void => {
      if (req.send(type, data) && before) restore.set(type, before);
      else if (before) set(before);
    };
    const load = (): void => {
      for (const t of ["role_list", "role_binding_list", "trait_list", "role_traits_list"]) req.send(t, "");
    };

    const off = link.onFrame((env) => {
      const mine = req.settle(env);
      const done = (type: string): void => void (mine && restore.delete(type));
      switch (env.type) {
        case "roles": {
          const list = json<Role[]>(env.data);
          if (list) set({ roles: byKey(list, (r) => r.id), loaded: true });
          return;
        }
        case "role": {
          const r = json<Role>(env.data);
          if (r?.id) set({ roles: { ...get().roles, [r.id]: r } });
          done("role_save");
          done("role_delete");
          return;
        }
        case "role_removed": {
          const id = env.data.replace(/^"|"$/g, "");
          const rest = { ...get().roles };
          delete rest[id];
          set({ roles: rest });
          done("role_delete");
          return;
        }
        case "role_bindings": {
          const list = json<RoleBinding[]>(env.data);
          if (list) set({ bindings: byKey(list, (b) => b.roleId) });
          return;
        }
        case "role_binding": {
          const b = json<RoleBinding>(env.data);
          if (b?.roleId) set({ bindings: { ...get().bindings, [b.roleId]: b } });
          done("role_binding_set");
          return;
        }
        case "traits": {
          const list = json<Trait[]>(env.data);
          if (list) set({ traits: byKey(list, (t) => t.id) });
          done("trait_delete");
          return;
        }
        case "trait_item": {
          const t = json<Trait>(env.data);
          if (t?.id) set({ traits: { ...get().traits, [t.id]: t } });
          done("trait_save");
          return;
        }
        case "role_traits_all": {
          const list = json<RoleTraits[]>(env.data);
          if (list) set({ roleTraits: Object.fromEntries(list.map((r) => [r.roleId, r.traitIds])) });
          return;
        }
        case "role_traits": {
          const r = json<RoleTraits>(env.data);
          if (r?.roleId) set({ roleTraits: { ...get().roleTraits, [r.roleId]: r.traitIds } });
          done("role_traits_set");
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
      roles: {},
      bindings: {},
      roleTraits: {},
      traits: {},
      loaded: false,
      refusal: null,

      ensureLoaded() {
        if (!get().loaded) load();
      },
      refresh() {
        load();
      },
      saveRole(role) {
        const { roles } = get();
        const old = roles[role.id];
        const fresh: Role = { id: role.id, name: role.name, persona: role.persona, source: "CUSTOM", icon: "", description: "", color: "" };
        const optimistic: Role = { ...(old ?? fresh), ...role };
        set({ roles: { ...roles, [role.id]: optimistic } });
        send("role_save", JSON.stringify(role), { roles });
      },
      deleteRole(id) {
        const { roles } = get();
        if (roles[id]?.source === "CUSTOM") {
          const rest = { ...roles };
          delete rest[id];
          set({ roles: rest });
        }
        send("role_delete", id, { roles });
      },
      setBinding(binding) {
        const { bindings } = get();
        const clean: RoleBinding = { roleId: binding.roleId, provider: binding.provider || null, model: binding.model || null, effort: binding.effort || null };
        set({ bindings: { ...bindings, [clean.roleId]: clean } });
        send("role_binding_set", JSON.stringify(clean), { bindings });
      },
      setRoleTraits(roleId, traitIds) {
        if (traitIds.length > MAX_ROLE_TRAITS) return set({ refusal: `A role takes at most ${MAX_ROLE_TRAITS} traits` });
        const { roleTraits } = get();
        set({ roleTraits: { ...roleTraits, [roleId]: traitIds } });
        send("role_traits_set", JSON.stringify({ roleId, traitIds }), { roleTraits });
      },
      saveTrait(trait) {
        const { traits } = get();
        set({ traits: { ...traits, [trait.id]: { ...(traits[trait.id] ?? {}), ...trait } } });
        send("trait_save", JSON.stringify(trait), { traits });
      },
      deleteTrait(id) {
        const { traits } = get();
        const rest = { ...traits };
        delete rest[id];
        set({ traits: rest });
        send("trait_delete", id, { traits });
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
