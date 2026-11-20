// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The window-wide keyboard shortcuts (@infantry/client shortcutOf), run against the UI state. Mounted once by AppShell.
import { useEffect, useRef } from "react";
import { CHAT_SEARCH_EVENT, shortcutOf, stepThrough, type Shortcut } from "@infantry/client";
import { useAgentList } from "@/daemon/agents";
import { useTabs } from "@/plugins/catalog";
import { useUi } from "@/state/ui-state";
import { daemonStore, localMode } from "@/daemon/daemon";
import { openSessionDialog } from "@/components/session/session-actions";

export const AGENT_SEARCH_ID = "agent-search";

const focusLater = (selector: string) => requestAnimationFrame(() => document.querySelector<HTMLElement>(selector)?.focus());

export function useShortcuts(): void {
  const ui = useUi();
  const agents = useAgentList();
  const centre = useTabs("center");
  const right = useTabs("right");
  // The handler reads the latest values without re-binding on every render.
  const now = useRef({ ui, agents, centre, right });
  now.current = { ui, agents, centre, right };

  useEffect(() => {
    const run = (s: Shortcut): boolean => {
      const { ui, agents, centre, right } = now.current;
      // The sidebar's order: running first, then quiet.
      const ordered = [...agents.filter((a) => a.group === "running"), ...agents.filter((a) => a.group !== "running")];
      switch (s.kind) {
        case "search":
          if (!ui.leftOpen) ui.toggle("leftOpen");
          focusLater(`#${AGENT_SEARCH_ID}`);
          return true;
        case "chatSearch":
          if (!ui.selectedAgentId) return false;
          ui.set("sessionTab", "cli");
          window.dispatchEvent(new Event(CHAT_SEARCH_EVENT));
          return true;
        case "switchAgent":
          if (!ui.selectedAgentId) return false;
          openSessionDialog("swap", ui.selectedAgentId);
          return true;
        case "endSession":
          if (!ui.selectedAgentId) return false;
          daemonStore().getState().sendFrame({ type: "kill", data: "", sessionId: ui.selectedAgentId });
          return true;
        case "newAgent":
          ui.set("dialog", "new-agent");
          return true;
        case "settings":
          ui.set("dialog", "settings");
          return true;
        case "resume":
          ui.set("dialog", "resume");
          return true;
        case "composer":
          ui.set("sessionTab", "cli");
          focusLater('textarea[aria-label="Prompt"]');
          return true;
        case "nextNeedsInput": {
          const waiting = ordered.filter((a) => a.status === "needs-input").map((a) => a.id);
          const next = stepThrough(waiting, ui.selectedAgentId, 1);
          if (!next) return false;
          ui.set("selectedAgentId", next);
          ui.set("sessionTab", "cli");
          return true;
        }
        case "agent": {
          const next = stepThrough(ordered.map((a) => a.id), ui.selectedAgentId, s.step);
          if (!next) return false;
          ui.set("selectedAgentId", next);
          return true;
        }
        case "tab": {
          if (s.digit === "8") {
            ui.set("sessionTab", "board");
            return true;
          }
          const hint = `^${s.digit}`;
          const c = centre.find((t) => t.hint === hint);
          if (c) {
            ui.set("sessionTab", c.id as typeof ui.sessionTab);
            return true;
          }
          const r = right.find((t) => t.hint === hint);
          if (!r) return false;
          ui.set("rightTab", r.id as typeof ui.rightTab);
          if (!ui.rightOpen) ui.toggle("rightOpen");
          return true;
        }
      }
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || e.isComposing) return;
      // Resume runs only in the embedded runner; elsewhere Ctrl+R is left to the browser, which reloads (UI-RESUME-WEB-1).
      const s = shortcutOf(e, { resume: localMode });
      if (s && run(s)) e.preventDefault();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);
}
