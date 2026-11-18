// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The bordered Settings card (heading, "applies to" line) and its rows (label + hint left, editor right).
import type { ReactNode } from "react";
import { Label } from "@infantry/ui";

export function SettingsCard({ title, applies, children }: { title: string; applies?: string | null; children: ReactNode }) {
  return (
    <section aria-label={title} className="space-y-2 rounded-lg border bg-card p-3">
      <div>
        <h3 className="text-[13px] font-semibold">{title}</h3>
        {applies && <p className="text-xs text-muted-foreground">Applies to: {applies}</p>}
      </div>
      <div className="divide-y">{children}</div>
    </section>
  );
}

export function SettingRow({ id, label, hint, error, children }: { id: string; label: string; hint?: string | null; error?: string; children: ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 py-2 first:pt-0 last:pb-0">
      <div className="min-w-0">
        <Label htmlFor={id}>{label}</Label>
        {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
        {error && (
          <p role="alert" className="text-xs text-danger">
            {error}
          </p>
        )}
      </div>
      <div className="flex shrink-0 items-center gap-2">{children}</div>
    </div>
  );
}
