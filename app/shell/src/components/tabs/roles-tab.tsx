// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Roles: one card per role with its icon and how many agents run it.
import { Plus } from "lucide-react";
import { agents, roles } from "@/mock/data";
import { Button } from "@infantry/ui";
import { Chip } from "@infantry/ui";
import { RoleIcon } from "@/components/agents/role-icon";
import { TabPage } from "./tab-page";

export function RolesTab() {
  return (
    <TabPage
      title="Roles"
      description="Role text holds preferences; product code holds mechanisms"
      actions={
        <Button variant="outline" size="sm">
          <Plus /> New role
        </Button>
      }
    >
      <div className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-3">
        {roles.map((r) => {
          const count = agents.filter((a) => a.roleId === r.id).length;
          return (
            <article key={r.id} className="flex items-center gap-3 rounded-lg border bg-card p-3">
              <div className="grid size-9 place-items-center rounded-md border bg-raised text-danger">
                <RoleIcon roleId={r.id} className="size-4" />
              </div>
              <div className="min-w-0 flex-1">
                <h3 className="font-medium capitalize">{r.label}</h3>
                <p className="text-xs text-muted-foreground">Project default model</p>
              </div>
              <Chip tone={count > 0 ? "green" : "neutral"}>{count} agents</Chip>
            </article>
          );
        })}
      </div>
    </TabPage>
  );
}
