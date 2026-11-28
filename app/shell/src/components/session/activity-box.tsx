// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The chat's bottom-right float row over the transcript (Kotlin ChatFeedSurface FloatRow + ActivityBox, CHAT-MINIMIZE-5):
// the host's floaters first (Expand, Minimize, or Restore alone when minimized), then the Search chip (Ctrl+F), then
// the Activity chip (a clock, icon only, no count; only when the session has activity). The Activity chip toggles the
// activity lines above the row: newest first, timestamped, monospace, at most ~160px tall, scrolling. It starts
// collapsed (setting chat.activityBox.open, PARITY-AUDIT-1 #13) and a toggle is remembered for the window.
import { useSyncExternalStore, type ReactNode } from "react";
import { Clock, Search } from "lucide-react";
import { CHAT_SEARCH_EVENT } from "@infantry/client";
import { useDaemon } from "@/daemon/daemon";
import { ChangeBoxOfWrite } from "./change-box";
import { FloatingChip } from "./floating-chip";
import { useChatSetting } from "./chat-settings";

// The Activity toggle is shared by the log (here) and its chip, which may sit in the composer's top tray
// (CHAT-TOOLBAR-TOP-1), so the remembered choice is a tiny module store.
let remembered: boolean | null = null;
const listeners = new Set<() => void>();
function useActivityOpen(): [boolean, (o: boolean) => void] {
  const startsOpen = useChatSetting("chat.activityBox.open", false);
  const toggled = useSyncExternalStore(
    (l) => (listeners.add(l), () => listeners.delete(l)),
    () => remembered,
    () => remembered,
  );
  return [toggled ?? startsOpen, (o) => ((remembered = o), listeners.forEach((l) => l()))];
}

const timeFmt = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit", second: "2-digit" });

/** The row's chips: the host's floaters, then Search, then Activity. Drawn in the float row, or in the composer's top tray. */
export function ChatTopChips({ sessionId, floaters }: { sessionId: string | null; floaters?: ReactNode }) {
  const has = useDaemon((s) => (sessionId ? (s.sessions[sessionId]?.activityLog?.length ?? 0) > 0 : false));
  const [open, setOpen] = useActivityOpen();
  return (
    <>
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
    </>
  );
}

/** `floaters`: the host's chips (Expand, Minimize / Restore), drawn first in the row. No session: floaters only.
 *  `rowless`: the chips are in the composer's top tray instead (CHAT-TOOLBAR-TOP-1); only the log is drawn here. */
export function ActivityBox({ sessionId, floaters, rowless = false }: { sessionId: string | null; floaters?: ReactNode; rowless?: boolean }) {
  const log = useDaemon((s) => (sessionId ? s.sessions[sessionId]?.activityLog : undefined));
  const [open] = useActivityOpen();
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
      {!rowless && (
        <div className="pointer-events-auto flex items-center pr-1.5 pb-0.5" data-testid="chat-float-row">
          <ChatTopChips sessionId={sessionId} floaters={floaters} />
        </div>
      )}
    </div>
  );
}
