// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Small pieces the knowledge, roles and library tabs share: a segmented switch and tag splitting. The refusal banner, the
// labelled field and `ago` live in the kit (`@infantry/ui`) since WEBUI-1, for web UI plugins; they are re-exported here.
import { cn } from "@infantry/ui";

export { Field, RefusalBanner, ago } from "@infantry/ui";

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

/** "a, b , c" -> ["a","b","c"]. */
export function splitTags(text: string): string[] {
  return [...new Set(text.split(",").map((t) => t.trim()).filter(Boolean))];
}
