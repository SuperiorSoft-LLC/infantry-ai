// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The daemon link as a dot and a line: connected / connecting / offline (the title bar and the status bar share it).
import { cn } from "@infantry/ui";
import { stressMode, useDaemon } from "@/daemon/daemon";

export function ConnectionBadge({ showUrl, className }: { showUrl?: boolean; className?: string }) {
  const c = useDaemon((s) => s.connection);
  const tone = stressMode ? "text-warn" : c.state === "open" ? "text-ok" : c.state === "connecting" ? "text-warn" : "text-danger";
  const dot = stressMode ? "bg-warn" : c.state === "open" ? "bg-ok" : c.state === "connecting" ? "bg-warn animate-pulse" : "bg-danger";
  const label = stressMode
    ? "stress mode (no daemon)"
    : c.state === "open"
      ? "connected"
      : c.state === "connecting"
        ? c.attempt > 0 ? `reconnecting (try ${c.attempt + 1})` : "connecting"
        : `offline${c.reason ? `: ${c.reason}` : ""}`;
  return (
    <span className={cn("flex items-center gap-1.5", tone, className)} data-testid="connection" data-state={stressMode ? "stress" : c.state}>
      <span className={cn("size-1.5 rounded-full", dot)} /> {label}
      {showUrl && !stressMode && ` ${c.url}`}
    </span>
  );
}
