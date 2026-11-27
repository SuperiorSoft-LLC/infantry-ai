// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// ChatPanel: THE chat (transcript, background jobs, composer) for one session. The React counterpart of the Kotlin
// ChatPanel composable: the centre column, the Assistant box and a "pop out this chat" window all render this one
// component, so message rendering, the image modal, the composer and the mail / comms cards are shared and never
// copied. `variant` only changes density: "full" is the centre column, "popout" a floating window, "compact" a popout
// without the job row. The composer's own layout does not depend on it: its width decides (Kotlin CHAT-MINIMIZE-5).
//
// CHAT-MINIMIZE-1/5: Expand / Collapse and Minimize float in the transcript's bottom-right row beside Search and
// Activity, never inside the box. Minimized, the whole composer (prompt card, queue, status line, box, groups, hint) is
// not rendered and the transcript takes the space; Restore ("Show composer") is the one floater left. The draft and the
// minimized / expanded state live per window (`@infantry/client` chat-view), so a restore brings the composer back as it was.
import { useEffect, type ReactNode } from "react";
import { Copy, Maximize2, Minimize2, Minus, Unplug } from "lucide-react";
import { EmptyState, cn } from "@infantry/ui";
import { useAgent } from "@/daemon/agents";
import { useDaemon } from "@/daemon/daemon";
import { toggleExpanded, toggleMinimized } from "@infantry/client";
import { useChatView } from "./use-chat-view";
import { Transcript } from "./transcript";
import { Composer } from "./composer";
import { ActivityBox, ChatTopChips } from "./activity-box";
import { BgJobsRow } from "./bg-jobs-row";
import { FloatingChip } from "./floating-chip";

export type ChatVariant = "full" | "popout" | "compact";

export interface ChatPanelProps {
  /** The session to show; null shows the empty state, and the composer starts a new session (unless [sessionRequired]). */
  sessionId: string | null;
  variant?: ChatVariant;
  /** The window this chat lives in; its minimized / expanded / draft state is remembered under this id. */
  windowId?: string;
  /** Shown instead of the default empty state when there is no session; [action] sits under the hint (a retry). */
  empty?: { title: string; hint: string; action?: ReactNode };
  /**
   * A chat for one session that something else makes (the Assistant box): with no session it shows the empty state alone,
   * with no composer, because a prompt typed there would start an ordinary agent in the agents area instead.
   */
  sessionRequired?: boolean;
  className?: string;
}

/** The composer's floaters (Kotlin ComposerFloaters): Expand/Collapse and Minimize; minimized, Restore alone. */
function ComposerFloaters({ windowId, minimized, expanded }: { windowId: string; minimized: boolean; expanded: boolean }) {
  if (minimized)
    return (
      <FloatingChip label="Show composer" onClick={() => toggleMinimized(windowId)}>
        <Copy />
      </FloatingChip>
    );
  return (
    <>
      <FloatingChip label={expanded ? "Collapse" : "Expand"} onClick={() => toggleExpanded(windowId)}>
        {expanded ? <Minimize2 /> : <Maximize2 />}
      </FloatingChip>
      <FloatingChip label="Minimize" onClick={() => toggleMinimized(windowId)}>
        <Minus />
      </FloatingChip>
    </>
  );
}

export function ChatPanel({ sessionId, variant = "full", windowId = "main", empty, sessionRequired, className }: ChatPanelProps) {
  const agent = useAgent(sessionId ?? "");
  const link = useDaemon((s) => s.connection.state);
  const requestHistory = useDaemon((s) => s.requestHistory);
  const busy = agent?.status === "working";
  const { minimized, expanded } = useChatView(windowId);
  // CHAT-GONE-1: an open chat whose session the daemon no longer has says so, and its composer must not spawn a new agent.
  const gone = useDaemon((s) => (sessionId ? s.gone[sessionId] : undefined));
  const goneId = !agent && gone ? sessionId : null;
  const waiting = !agent && !goneId && !!sessionRequired;

  // A session opened in a view brings its history, if the burst did not already ask for it.
  useEffect(() => {
    if (agent) requestHistory(agent.id);
  }, [agent?.id, requestHistory]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className={cn("flex min-h-0 min-w-0 flex-1 flex-col", className)} data-chat-variant={variant}>
      <div className="relative flex min-h-0 flex-1 flex-col">
        {agent ? (
          <Transcript agent={agent} sessionId={agent.id} />
        ) : (
          <div className="grid flex-1 place-items-center p-6">
            <EmptyState
              icon={<Unplug className="size-7" />}
              title={goneId ? "This worker is gone" : (empty?.title ?? (link === "open" ? "No session selected" : "Not connected to the daemon"))}
              hint={goneId ? "It has finished and was removed; messages to it are not sent. Start a new agent to go on." : (empty?.hint ?? (link === "open" ? "Send a prompt below to start a new session." : "The app reconnects on its own."))}
              action={goneId ? undefined : empty?.action}
            />
          </div>
        )}
        {!waiting && <ActivityBox sessionId={agent?.id ?? null} rowless={!minimized} floaters={<ComposerFloaters windowId={windowId} minimized={minimized} expanded={expanded} />} />}
      </div>
      {agent && variant !== "compact" && <BgJobsRow sessionId={agent.id} />}
      {!waiting && !minimized && (
        <Composer
          sessionId={agent?.id ?? goneId}
          busy={busy && !gone}
          windowId={windowId}
          topTray={<ChatTopChips sessionId={agent?.id ?? null} floaters={<ComposerFloaters windowId={windowId} minimized={minimized} expanded={expanded} />} />}
        />
      )}
    </div>
  );
}

/** The centre column's chat. */
export function FullChatPanel({ sessionId }: { sessionId: string | null }) {
  return <ChatPanel sessionId={sessionId} variant="full" />;
}
