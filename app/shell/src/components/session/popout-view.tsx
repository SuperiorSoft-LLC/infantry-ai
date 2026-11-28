// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// POPOUT-1: the page of a native pop-out window: one session's chat, no sidebar, title bar or panels. Its own daemon
// connection (the page's, as in the main window) keeps it in sync; the OS window bar carries the title and the buttons.
import { useEffect } from "react";
import { ChatPanel } from "./chat-panel";
import { useAgent } from "@/daemon/agents";
import { ToastHost } from "@/components/layout/toast-host";

export function PopoutView({ sessionId }: { sessionId: string }) {
  const name = useAgent(sessionId)?.name;
  useEffect(() => {
    if (name) document.title = name;
  }, [name]);
  return (
    <div className="flex h-screen w-screen flex-col overflow-hidden bg-background text-foreground" data-popout="session">
      <ChatPanel sessionId={sessionId} variant="full" windowId={`native-${sessionId}`} sessionRequired empty={{ title: "Session not found", hint: "It may have been removed, or the daemon is still connecting." }} />
      <ToastHost />
    </div>
  );
}
