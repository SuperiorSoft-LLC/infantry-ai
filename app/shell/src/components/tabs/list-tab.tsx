// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Generic list tab: a title, an action and ListRow cards. Pods, Profiles, Triggers, Epics, Notes, Run, Git, Services use it.
import type { ReactNode } from "react";
import { Plus, Inbox } from "lucide-react";
import type { NamedRow } from "@/mock/types";
import { Button } from "@infantry/ui";
import { ListRow } from "@infantry/ui";
import { EmptyState } from "@infantry/ui";
import { TabPage } from "./tab-page";

export function ListTab({
  title,
  description,
  rows,
  icon,
  actionLabel,
}: {
  title: string;
  description?: string;
  rows: NamedRow[];
  icon: ReactNode;
  actionLabel?: string;
}) {
  return (
    <TabPage
      title={title}
      description={description}
      actions={
        actionLabel && (
          <Button variant="outline" size="sm">
            <Plus /> {actionLabel}
          </Button>
        )
      }
    >
      {rows.length === 0 ? (
        <EmptyState icon={<Inbox className="size-6" />} title={`No ${title.toLowerCase()} yet`} />
      ) : (
        <div className="space-y-2">
          {rows.map((r) => (
            <ListRow key={r.id} row={r} icon={icon} />
          ))}
        </div>
      )}
    </TabPage>
  );
}
