// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Shared shell for every non-CLI tab: title row with actions, then a scrolling body. In the kit (WEBUI-1) so a web UI
// plugin's tab is framed exactly like the app's own.
import type { ReactNode } from "react";
import { ScrollArea } from "../components/scroll-area";

export function TabPage({ title, description, actions, children }: { title: string; description?: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3">
        <div className="min-w-0">
          <h2 className="text-base font-semibold">{title}</h2>
          {description && <p className="text-xs text-muted-foreground">{description}</p>}
        </div>
        {actions && <div className="flex gap-2">{actions}</div>}
      </div>
      <ScrollArea className="min-h-0 flex-1">
        <div className="p-4">{children}</div>
      </ScrollArea>
    </div>
  );
}
