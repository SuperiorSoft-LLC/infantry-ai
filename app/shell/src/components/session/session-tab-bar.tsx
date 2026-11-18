// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// CLI, Pods, Roles ... Services. Scrolls horizontally when narrow.
import {
  BarChart3,
  Box,
  Calendar,
  FileText,
  GitBranch,
  IdCard,
  Layers,
  Play,
  Puzzle,
  Server,
  SquareTerminal,
  TrendingUp,
  User,
  Zap,
  type LucideIcon,
} from "lucide-react";
import { cn } from "@infantry/ui";
import { sessionTabs, type SessionTabId } from "@/mock/data";
import { KeyHint } from "@infantry/ui";

const icons: Record<string, LucideIcon> = {
  terminal: SquareTerminal,
  box: Box,
  user: User,
  "id-card": IdCard,
  zap: Zap,
  calendar: Calendar,
  layers: Layers,
  "file-text": FileText,
  play: Play,
  "git-branch": GitBranch,
  puzzle: Puzzle,
  "bar-chart": BarChart3,
  "trending-up": TrendingUp,
  server: Server,
};

export function SessionTabBar({ value, onChange }: { value: string; onChange: (id: SessionTabId) => void }) {
  return (
    <nav role="tablist" aria-label="Session views" className="no-scrollbar flex gap-1 overflow-x-auto border-b px-3">
      {sessionTabs.map((t) => {
        const Icon = icons[t.icon];
        const active = t.id === value;
        return (
          <button
            key={t.id}
            role="tab"
            aria-selected={active}
            onClick={() => onChange(t.id)}
            className={cn(
              "relative flex h-10 shrink-0 items-center gap-1.5 px-2.5 text-[13px] transition-colors",
              active ? "font-semibold text-danger" : "text-muted-foreground hover:text-foreground",
            )}
          >
            <Icon className="size-3.5" aria-hidden />
            {t.label}
            {"hint" in t && <KeyHint className={active ? "text-danger/80" : ""}>{t.hint}</KeyHint>}
            {active && <span className="absolute inset-x-1 -bottom-px h-0.5 rounded-full bg-danger" />}
          </button>
        );
      })}
    </nav>
  );
}
