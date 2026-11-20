// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Activity box (Kotlin ActivityBox): the session's tool activity, newest first, timestamped, in a panel over the
// transcript's bottom edge. A chip toggles it (show / minimize); the header button maximizes it to a tall panel.
// The choice is remembered for the window.
import { useState, type ReactNode } from "react";
import { Activity, Maximize2, Minimize2, X } from "lucide-react";
import { IconButton, cn } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";

type BoxMode = "closed" | "open" | "max";
let remembered: BoxMode = "closed";

const timeFmt = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit", second: "2-digit" });

/** `actions`: the chat view buttons (Expand, Minimize / Restore) that float in the same bottom-right row as the one chip. */
export function ActivityBox({ sessionId, actions }: { sessionId: string; actions?: ReactNode }) {
  const log = useDaemon((s) => s.sessions[sessionId]?.activityLog);
  const [mode, setModeState] = useState<BoxMode>(remembered);
  const setMode = (m: BoxMode) => ((remembered = m), setModeState(m));
  const lines = [...(log ?? [])].reverse();
  const has = lines.length > 0;
  return (
    <div className="pointer-events-none absolute right-2 bottom-1 left-2 z-10 flex flex-col items-end gap-1" data-testid="activity-box">
      {has && mode !== "closed" && (
        <div className="pointer-events-auto w-full rounded-lg border bg-card">
          <div className="flex items-center justify-between border-b px-2 py-1 text-xs text-muted-foreground">
            <span>Activity · {lines.length}</span>
            <span className="flex gap-1">
              <IconButton label={mode === "max" ? "Minimize" : "Maximize"} onClick={() => setMode(mode === "max" ? "open" : "max")}>
                {mode === "max" ? <Minimize2 className="size-3.5" /> : <Maximize2 className="size-3.5" />}
              </IconButton>
              <IconButton label="Hide activity" onClick={() => setMode("closed")}>
                <X className="size-3.5" />
              </IconButton>
            </span>
          </div>
          <div className={cn("overflow-y-auto px-2 py-1 font-mono text-[11px]", mode === "max" ? "max-h-[60vh]" : "max-h-40")}>
            {lines.map((e, i) => (
              <div key={`${e.at}:${i}`} className="flex gap-2">
                <span className="shrink-0 text-muted-foreground">{timeFmt.format(e.at)}</span>
                <span className="min-w-0 break-all">{e.label}</span>
              </div>
            ))}
          </div>
        </div>
      )}
      <div className="pointer-events-auto flex items-center gap-1" data-testid="chat-float-row">
        {has && mode === "closed" && (
          <button
            type="button"
            onClick={() => setMode("open")}
            title="Activity"
            aria-label="Activity"
            className="grid size-8 place-items-center rounded-full border bg-card text-muted-foreground hover:text-foreground max-sm:size-12"
          >
            <Activity className="size-4" />
          </button>
        )}
        {actions}
      </div>
    </div>
  );
}
