// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The chat's bottom-right float row over the transcript (Kotlin ChatFeedSurface FloatRow + ActivityBox, CHAT-MINIMIZE-5):
// the host's floaters first (Expand, Minimize, or Restore alone when minimized), then the Search chip (Ctrl+F), then
// the Activity chip (a clock, icon only, no count; only when the session has activity). The Activity chip toggles the
// activity lines above the row: newest first, timestamped, monospace, at most ~160px tall, scrolling. It starts
// collapsed and the choice is remembered for the window.
import { useState, type ReactNode } from "react";
import { Clock, Search } from "lucide-react";
import { CHAT_SEARCH_EVENT } from "@infantry/client";
import { useDaemon } from "@/daemon/daemon";
import { ChangeBoxOfWrite } from "./change-box";
import { FloatingChip } from "./floating-chip";

let remembered = false;

const timeFmt = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit", second: "2-digit" });

/** `floaters`: the host's chips (Expand, Minimize / Restore), drawn first in the row. No session: floaters only. */
export function ActivityBox({ sessionId, floaters }: { sessionId: string | null; floaters?: ReactNode }) {
  const log = useDaemon((s) => (sessionId ? s.sessions[sessionId]?.activityLog : undefined));
  const [open, setOpenState] = useState(remembered);
  const setOpen = (o: boolean) => ((remembered = o), setOpenState(o));
  const lines = [...(log ?? [])].reverse();
  const has = lines.length > 0;
  return (
    <div className="pointer-events-none absolute right-0 bottom-0 left-2 z-10 flex flex-col items-end" data-testid="activity-box">
      {has && open && (
        <div className="pointer-events-auto max-h-40 w-[calc(100%-0.5rem)] overflow-y-auto rounded-lg border bg-card px-3 py-1 font-mono text-[11px]" aria-label="Activity">
          {lines.map((e, i) => (
            <div key={`${e.at}:${i}`} className="flex gap-2 py-px">
              <span className="shrink-0 text-muted-foreground">{timeFmt.format(e.at)}</span>
              {e.write ? (
                <div className="min-w-0 flex-1 py-0.5">
                  <ChangeBoxOfWrite write={e.write} />
                </div>
              ) : (
                <span className="min-w-0 break-all">{e.label}</span>
              )}
            </div>
          ))}
        </div>
      )}
      <div className="pointer-events-auto flex items-center pr-1.5 pb-0.5" data-testid="chat-float-row">
        {floaters}
        {sessionId && (
          <FloatingChip label="Search this chat" shortcut="Ctrl+F" onClick={() => window.dispatchEvent(new Event(CHAT_SEARCH_EVENT))}>
            <Search />
          </FloatingChip>
        )}
        {has && (
          <FloatingChip label="Activity" onClick={() => setOpen(!open)}>
            <Clock />
          </FloatingChip>
        )}
      </div>
    </div>
  );
}
