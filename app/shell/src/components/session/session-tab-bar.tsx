// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The centre tab bar. Its tabs come from the plugin registry (plugins/catalog.tsx `tabsFor("center")`): an enabled
// plugin's declared tab appears, a disabled one's goes. Scrolls horizontally when narrow.
import { cn, KeyHint } from "@infantry/ui";
import type { TabEntry } from "@/plugins/catalog";

export function SessionTabBar({ tabs, value, onChange }: { tabs: TabEntry[]; value: string; onChange: (id: string) => void }) {
  return (
    <nav role="tablist" aria-label="Session views" className="no-scrollbar flex min-w-0 max-w-full shrink-0 gap-1 overflow-x-auto border-b px-3">
      {tabs.map((t) => {
        const Icon = t.icon;
        const active = t.id === value;
        return (
          <button
            key={t.id}
            role="tab"
            aria-selected={active}
            title={t.plugin ? `${t.label} (${t.plugin})` : t.label}
            onClick={() => onChange(t.id)}
            className={cn(
              "relative flex h-10 shrink-0 items-center gap-1.5 px-2.5 text-[13px] transition-colors",
              active ? "font-semibold text-danger" : "text-muted-foreground hover:text-foreground",
            )}
          >
            <Icon className="size-3.5" aria-hidden />
            {t.label}
            {t.hint && <KeyHint className={active ? "text-danger/80" : ""}>{t.hint}</KeyHint>}
            {active && <span className="absolute inset-x-1 -bottom-px h-0.5 rounded-full bg-danger" />}
          </button>
        );
      })}
    </nav>
  );
}
