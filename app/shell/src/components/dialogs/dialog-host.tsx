// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Renders whichever dialog the UI state names, always through the one AppDialog.
import { useEffect, useState } from "react";
import { useUi } from "@/state/ui-state";
import { Button } from "@infantry/ui";
import { AppDialog } from "@infantry/ui";
import { daemonStore, hopsOpenBoard, localMode } from "@/daemon/daemon";
import { TicketDialog } from "@/components/board/ticket-dialog";
import { EMPTY_DRAFT, NewAgentBody, addAgent, addBlockedReason, type NewAgentDraft } from "./new-agent-dialog";
import { ResumeBody } from "./resume-body";
import { PluginSettingsDialog } from "@/components/plugins/plugin-settings-dialog";
import { SettingsBody } from "./settings-body";
import { BoardDialog } from "@/components/board/board-dialog";
import { StatsDialog } from "@/components/stats/stats-dialog";
import { SetupWizardDialog } from "@/components/setup/setup-wizard";

const config = {
  settings: { title: "Settings", description: "Appearance, daemon and notifications", primary: "Done", wide: "fill" },
  "new-agent": { title: "New agent", description: "Say what it should do first. A role is optional; the defaults start an agent.", primary: "Add", wide: true },
  resume: { title: "Resume session", description: "Pick a project folder, then one of its Claude sessions.", primary: "Resume", wide: true },
} as const;

export function DialogHost() {
  const ui = useUi();
  const [agent, setAgent] = useState<NewAgentDraft>(EMPTY_DRAFT);
  const [folder, setFolder] = useState<string | null>(null);
  const [picked, setPicked] = useState<string | null>(null);
  // UI-RESUME-WEB-1: Resume runs only in the embedded runner; anywhere else no dialog offers it.
  const dialog = ui.dialog === "resume" && !localMode ? null : ui.dialog;
  // Hop timings run (INFANTRY_UI_HOPS=board): open the board once, as the board button does.
  useEffect(() => {
    if (hopsOpenBoard) ui.set("dialog", "board");
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  useEffect(() => {
    // The new-agent draft is cleared when its dialog closes, not when it opens: the body seeds the default harness as it
    // mounts (its effect runs before this one), so a reset on open wiped that whenever the harness list was already loaded
    // and left "Pick a harness" with Add disabled.
    if (dialog !== "new-agent") setAgent(EMPTY_DRAFT);
    if (dialog === "resume") setPicked(null);
  }, [dialog]);
  if (!dialog) return <PluginSettingsDialog />;
  if (dialog === "new-ticket") return <TicketDialog ticketId={null} creating onClose={() => ui.set("dialog", null)} />;
  if (dialog === "board") return <BoardDialog onClose={() => ui.set("dialog", null)} />;
  if (dialog === "stats") return <StatsDialog onClose={() => ui.set("dialog", null)} />;
  if (dialog === "setup") return <SetupWizardDialog runner={ui.setupRunner} onClose={() => ui.set("dialog", null)} />;
  const c = config[dialog];
  const close = () => ui.set("dialog", null);
  const blocked = dialog === "new-agent" ? addBlockedReason(agent) : null;
  const primary = () => {
    if (dialog === "new-agent") {
      if (blocked) return;
      addAgent(agent);
    } else if (dialog === "resume" && picked) {
      daemonStore().getState().spawnWith({ resume: picked, cwd: folder ?? undefined });
      ui.set("selectedAgentId", picked);
    }
    close();
  };
  const disabled = (dialog === "resume" && !picked) || blocked !== null;
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && close()}
      title={c.title}
      description={c.description}
      wide={c.wide}
      footer={
        <>
          {blocked && <span className="mr-auto self-center text-xs text-info">↓ {blocked}</span>}
          <Button variant="ghost" onClick={close}>
            Cancel
          </Button>
          <Button onClick={primary} disabled={disabled}>
            {c.primary}
          </Button>
        </>
      }
    >
      {dialog === "settings" && <SettingsBody />}
      {dialog === "new-agent" && <NewAgentBody draft={agent} onChange={setAgent} onSubmit={primary} />}
      {dialog === "resume" && <ResumeBody folder={folder} onFolder={setFolder} picked={picked} onPick={setPicked} />}
    </AppDialog>
  );
}
