// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// ChatPanel: THE chat (transcript, prompt cards, background jobs, status line, composer) for one session. The React
// counterpart of the Kotlin ChatPanel composable: the centre column, the Assistant box and a "pop out this chat" window
// all render this one component, so message rendering, the image modal, the composer and the mail / comms cards are
// shared and never copied. `variant` only changes density: "full" is the centre column, "popout" a floating window,
// "compact" a popout without the activity box and job row.
import { useEffect } from "react";
import { Unplug } from "lucide-react";
import { EmptyState, cn } from "@infantry/ui";
import { useAgent, useAgentList } from "@/daemon/agents";
import { useDaemon } from "@/daemon/daemon";
import { useUi } from "@/state/ui-state";
import { Transcript } from "./transcript";
import { Composer, type ChatViewControl } from "./composer";
import { PromptCards } from "./prompt-cards";
import { ActivityBox } from "./activity-box";
import { BgJobsRow } from "./bg-jobs-row";

export type ChatVariant = "full" | "popout" | "compact";

export interface ChatPanelProps {
  /** The session to show; null shows the empty state, and the composer starts a new session. */
  sessionId: string | null;
  variant?: ChatVariant;
  /** Maximize / minimize for the composer toolbar. Omit it and the buttons are not drawn. */
  view?: ChatViewControl;
  /** Shown instead of the default empty state when there is no session. */
  empty?: { title: string; hint: string };
  className?: string;
}

export function ChatPanel({ sessionId, variant = "full", view, empty, className }: ChatPanelProps) {
  const agent = useAgent(sessionId ?? "");
  const link = useDaemon((s) => s.connection.state);
  const requestHistory = useDaemon((s) => s.requestHistory);
  const busy = agent?.status === "working";
  const agentCount = useAgentList().length;
  const full = variant === "full";

  // A session opened in a view brings its history, if the burst did not already ask for it.
  useEffect(() => {
    if (agent) requestHistory(agent.id);
  }, [agent?.id, requestHistory]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className={cn("flex min-h-0 min-w-0 flex-1 flex-col", className)} data-chat-variant={variant}>
      <div className="relative flex min-h-0 flex-1 flex-col">
        {agent && variant !== "compact" && <ActivityBox sessionId={agent.id} />}
        {agent ? (
          <Transcript agent={agent} sessionId={agent.id} />
        ) : (
          <div className="grid flex-1 place-items-center p-6">
            <EmptyState
              icon={<Unplug className="size-7" />}
              title={empty?.title ?? (link === "open" ? "No session selected" : "Not connected to the daemon")}
              hint={empty?.hint ?? (link === "open" ? "Send a prompt below to start a new session." : "The app reconnects on its own.")}
            />
          </div>
        )}
      </div>
      {agent && <PromptCards sessionId={agent.id} />}
      {agent && variant !== "compact" && <BgJobsRow sessionId={agent.id} />}
      {full && (
        <div className="px-4 pt-2 pb-1.5 font-mono text-[11px] text-muted-foreground">
          {agentCount} agent{agentCount === 1 ? "" : "s"} · {link !== "open" ? "not connected: commands are disabled" : agent ? (busy ? "working" : "idle") : "no session"}
        </div>
      )}
      <Composer sessionId={agent?.id ?? null} busy={busy} compact={!full} view={view} />
    </div>
  );
}

/** The centre column's chat: maximize maps to `ui.chatMax`, which hides the header and tab bar. */
export function FullChatPanel({ sessionId }: { sessionId: string | null }) {
  const ui = useUi();
  return <ChatPanel sessionId={sessionId} variant="full" view={{ max: ui.chatMax, onMax: () => ui.set("chatMax", true), onMin: () => ui.set("chatMax", false) }} />;
}
