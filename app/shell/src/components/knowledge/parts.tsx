// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Small pieces the knowledge, roles and library tabs share: the refusal banner, a segmented switch, a labelled field.
import type { ReactNode } from "react";
import { Button, Label, cn } from "@infantry/ui";

export function RefusalBanner({ text, onDismiss }: { text: string | null; onDismiss: () => void }) {
  if (!text) return null;
  return (
    <div role="alert" className="mb-3 flex items-center justify-between rounded-lg border border-danger/60 bg-danger/10 px-3 py-2 text-xs text-danger">
      <span>{text}</span>
      <Button variant="ghost" size="xs" onClick={onDismiss}>
        Dismiss
      </Button>
    </div>
  );
}

export function Segmented<T extends string>({ value, options, onChange, className }: { value: T; options: { id: T; label: string }[]; onChange: (v: T) => void; className?: string }) {
  return (
    <div className={cn("flex gap-1 rounded-lg border bg-card p-1", className)}>
      {options.map((o) => (
        <button
          key={o.id}
          type="button"
          onClick={() => onChange(o.id)}
          className={cn("flex-1 rounded-md px-2 py-1 text-xs whitespace-nowrap", value === o.id ? "bg-raised font-medium" : "text-muted-foreground hover:bg-raised/60")}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Field({ id, label, children, hint }: { id: string; label: string; children: ReactNode; hint?: string }) {
  return (
    <div className="space-y-1">
      <Label htmlFor={id}>{label}</Label>
      {children}
      {hint && <p className="text-[11px] text-muted-foreground">{hint}</p>}
    </div>
  );
}

export function ago(at?: number | null): string {
  if (!at) return "";
  const s = Math.max(0, Math.round((Date.now() - at) / 1000));
  if (s < 60) return "now";
  if (s < 3600) return `${Math.round(s / 60)}m`;
  if (s < 86_400) return `${Math.round(s / 3600)}h`;
  return `${Math.round(s / 86_400)}d`;
}

/** "a, b , c" -> ["a","b","c"]. */
export function splitTags(text: string): string[] {
  return [...new Set(text.split(",").map((t) => t.trim()).filter(Boolean))];
}
