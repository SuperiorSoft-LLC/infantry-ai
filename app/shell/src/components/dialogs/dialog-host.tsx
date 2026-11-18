// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Renders whichever dialog the UI state names, always through the one AppDialog.
import { useEffect, useState } from "react";
import { useUi } from "@/state/ui-state";
import { Button } from "@infantry/ui";
import { AppDialog } from "@infantry/ui";
import { daemonStore, localMode } from "@/daemon/daemon";
import { TicketDialog } from "@/components/board/ticket-dialog";
import { EMPTY_DRAFT, NewAgentBody, addAgent, addBlockedReason, type NewAgentDraft } from "./new-agent-dialog";
import { ResumeBody } from "./resume-body";
import { SettingsBody } from "./settings-body";

const config = {
  settings: { title: "Settings", description: "Appearance, daemon and notifications", primary: "Done", wide: true },
  "new-agent": { title: "New agent", description: "Pick a role, check the model, say what it should do first.", primary: "Add", wide: true },
  resume: { title: "Resume session", description: "Pick a project folder, then one of its Claude sessions.", primary: "Resume", wide: true },
} as const;

export function DialogHost() {
  const ui = useUi();
  const [agent, setAgent] = useState<NewAgentDraft>(EMPTY_DRAFT);
  const [folder, setFolder] = useState<string | null>(null);
  const [picked, setPicked] = useState<string | null>(null);
  useEffect(() => {
    if (ui.dialog === "new-agent") setAgent(EMPTY_DRAFT);
    if (ui.dialog === "resume") setPicked(null);
  }, [ui.dialog]);
  if (!ui.dialog) return null;
  if (ui.dialog === "new-ticket") return <TicketDialog ticketId={null} creating onClose={() => ui.set("dialog", null)} />;
  const c = config[ui.dialog];
  const close = () => ui.set("dialog", null);
  const blocked = ui.dialog === "new-agent" ? addBlockedReason(agent) : null;
  const primary = () => {
    if (ui.dialog === "new-agent") {
      if (blocked) return;
      addAgent(agent);
    } else if (ui.dialog === "resume" && picked) {
      daemonStore().getState().spawnWith({ resume: picked, cwd: folder ?? undefined });
      ui.set("selectedAgentId", picked);
    }
    close();
  };
  const disabled = (ui.dialog === "resume" && (!localMode || !picked)) || blocked !== null;
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
      {ui.dialog === "settings" && <SettingsBody />}
      {ui.dialog === "new-agent" && <NewAgentBody draft={agent} onChange={setAgent} onSubmit={primary} />}
      {ui.dialog === "resume" &&
        (localMode ? (
          <ResumeBody folder={folder} onFolder={setFolder} picked={picked} onPick={setPicked} />
        ) : (
          <div className="text-sm text-muted-foreground">Resume runs in the embedded runner; this window is connected to a daemon.</div>
        ))}
    </AppDialog>
  );
}
