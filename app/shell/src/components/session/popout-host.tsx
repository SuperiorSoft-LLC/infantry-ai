// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Every floating chat in the window: the Assistant box (top-bar button) and any session popped out from its header.
// All of them are a ChatPopout around the one ChatPanel.
import { useEffect, useState } from "react";
import { Button, Chip } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { useAgent } from "@/daemon/agents";
import { useUi } from "@/state/ui-state";
import { ChatPopout } from "./chat-popout";
import { useAssistantAsk, useAssistantId } from "./use-assistant";

/** What the box says while there is no Assistant to show: the link is down, the daemon refused, or the Assistant is on its way. */
function assistantEmpty(online: boolean, refusal: string | null, retry: () => void) {
  if (!online) return { title: "Not connected to the daemon", hint: "The app reconnects on its own." };
  if (refusal)
    return {
      title: "The Assistant did not start",
      hint: refusal,
      action: (
        <Button variant="outline" size="sm" onClick={retry}>
          Try again
        </Button>
      ),
    };
  return { title: "Starting the Assistant…", hint: "It opens here as soon as the daemon has it ready." };
}

/**
 * The Assistant box. The daemon makes the one installation-wide Assistant (`record.global`) when the box asks for it, and it
 * lives here: it is no card of the agents lists (`useAgentList` leaves it out), and with none yet the box has no composer,
 * so nothing typed here can start an ordinary agent instead.
 */
function AssistantBox({ z, onFocus }: { z: number; onFocus: () => void }) {
  const ui = useUi();
  const id = useAssistantId();
  const assistant = useAgent(id ?? "");
  const online = useDaemon((s) => s.connection.state === "open");
  const { refusal, ask } = useAssistantAsk();
  // Opening the box asks the daemon for the Assistant (it makes it on the first ask and pushes it as a `session` frame).
  useEffect(() => {
    if (online && !id) ask();
  }, [online, id, ask]);
  return (
    <ChatPopout
      id="assistant"
      title="Assistant"
      sessionId={id}
      sessionRequired
      badge={assistant && <Chip tone="green">{`${assistant.provider} · ${assistant.model}`}</Chip>}
      z={z}
      onFocus={onFocus}
      onClose={() => ui.set("assistantOpen", false)}
      empty={assistantEmpty(online, refusal, ask)}
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
