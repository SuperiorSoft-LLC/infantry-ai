// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Bottom status bar: connection, resources, update, keyboard hints, build.
import { AlertTriangle, Bug } from "lucide-react";
import { statusBar } from "@/mock/data";
import { KeyHint } from "@infantry/ui";
import { ConnectionBadge } from "./connection-badge";

export function StatusBar() {
  return (
    <footer className="flex h-6 shrink-0 items-center gap-4 border-t bg-background px-3 text-[11px] whitespace-nowrap">
      <ConnectionBadge />
      <span className="hidden text-muted-foreground sm:inline">{statusBar.lastFrame}</span>
      <span className="flex items-center gap-1 text-warn">
        <AlertTriangle className="size-3" /> {statusBar.ramFree} · {statusBar.agentsCpu}
      </span>
      <button type="button" className="hidden truncate text-ok hover:underline md:inline">
        {statusBar.update}
      </button>
      <span className="flex-1" />
      <span className="hidden items-center gap-3 xl:flex">
        {statusBar.hints.map((h) => (
          <KeyHint key={h}>{h}</KeyHint>
        ))}
      </span>
      <span className="hidden font-mono text-muted-foreground lg:inline">{statusBar.build}</span>
      <Bug className="size-3 text-muted-foreground" aria-hidden />
    </footer>
  );
}
