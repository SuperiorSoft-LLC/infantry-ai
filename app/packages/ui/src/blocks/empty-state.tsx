// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Deliberate empty state: icon, one line of what is missing, optional action.
import type { ReactNode } from "react";

export function EmptyState({ icon, title, hint, action }: { icon: ReactNode; title: string; hint?: string; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 rounded-lg border border-dashed p-6 text-center text-muted-foreground">
      <div className="text-muted-foreground/70">{icon}</div>
      <div className="text-sm font-medium text-foreground">{title}</div>
      {hint && <div className="text-xs">{hint}</div>}
      {action}
    </div>
  );
}
