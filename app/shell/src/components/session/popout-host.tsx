// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Every floating chat in the window: the Assistant box (top-bar button) and any session popped out from its header.
// All of them are a ChatPopout around the one ChatPanel.
import { useEffect, useState } from "react";
import { useDaemon } from "@/daemon/daemon";
import { useAgent } from "@/daemon/agents";
import { useUi } from "@/state/ui-state";
import { ChatPopout } from "./chat-popout";

/** The installation-wide Assistant session (`record.global`), or null until the daemon has made it. */
export function useAssistantId(): string | null {
  return useDaemon((s) => {
    for (const v of Object.values(s.sessions)) if (v.record.global) return v.record.id;
    return null;
  });
}

function AssistantBox({ z, onFocus }: { z: number; onFocus: () => void }) {
  const ui = useUi();
  const id = useAssistantId();
  const online = useDaemon((s) => s.connection.state === "open");
  const sendFrame = useDaemon((s) => s.sendFrame);
  // Opening the box asks the daemon for the Assistant (it makes it on the first ask and answers with a `session` frame).
  useEffect(() => {
    if (online && !id) sendFrame({ type: "assistant", data: "" });
  }, [online, id, sendFrame]);
  return (
    <ChatPopout
      id="assistant"
      title="Assistant"
      sessionId={id}
      z={z}
      onFocus={onFocus}
      onClose={() => ui.set("assistantOpen", false)}
      empty={{
        title: online ? "Starting the Assistant…" : "Not connected to the daemon",
        hint: online ? "If this stays empty, this daemon does not provide the Assistant yet." : "The app reconnects on its own.",
      }}
    />
  );
}

function SessionPopout({ sessionId, z, onFocus }: { sessionId: string; z: number; onFocus: () => void }) {
  const ui = useUi();
  const agent = useAgent(sessionId);
  // A session that is gone closes its window.
  useEffect(() => {
    if (!agent) ui.closePopout(sessionId);
  }, [agent, sessionId, ui]);
  return (
    <ChatPopout
      id={`session.${sessionId}`}
      title={agent ? `${agent.name} · ${agent.sessionId}` : "Chat"}
      sessionId={sessionId}
      z={z}
      onFocus={onFocus}
      onClose={() => ui.closePopout(sessionId)}
    />
  );
}

export function PopoutHost() {
  const ui = useUi();
  // Click-to-front: the last focused window gets the highest z.
  const [order, setOrder] = useState<string[]>([]);
  const front = (k: string) => setOrder((o) => (o[o.length - 1] === k ? o : [...o.filter((x) => x !== k), k]));
  const z = (k: string) => 40 + Math.max(0, order.indexOf(k));
  return (
    <>
      {ui.assistantOpen && <AssistantBox z={z("assistant")} onFocus={() => front("assistant")} />}
      {ui.popouts.map((id) => (
        <SessionPopout key={id} sessionId={id} z={z(id)} onFocus={() => front(id)} />
      ))}
    </>
  );
}
