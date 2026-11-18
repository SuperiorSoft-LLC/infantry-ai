// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Renders whichever dialog the UI state names, always through the one AppDialog.
import { useEffect, useState } from "react";
import { useUi } from "@/state/ui-state";
import { Button } from "@infantry/ui";
import { AppDialog } from "@infantry/ui";
import { daemonStore, localMode } from "@/daemon/daemon";
import { EMPTY_AGENT, NewAgentBody, NewTicketBody, type NewAgentForm } from "./create-bodies";
import { INHERIT } from "./picker-field";
import { ResumeBody } from "./resume-body";
import { SettingsBody } from "./settings-body";

const config = {
  settings: { title: "Settings", description: "Appearance, daemon and notifications", primary: "Done", wide: true },
  "new-agent": { title: "New agent", description: "Only a name is needed. Everything else inherits.", primary: "Create agent", wide: false },
  "new-ticket": { title: "New ticket", description: "Only a title is needed. Everything else inherits.", primary: "Create ticket", wide: false },
  resume: { title: "Resume session", description: "Pick a project folder, then one of its Claude sessions.", primary: "Resume", wide: true },
} as const;

export function DialogHost() {
  const ui = useUi();
  const [agent, setAgent] = useState<NewAgentForm>(EMPTY_AGENT);
  const [folder, setFolder] = useState<string | null>(null);
  const [picked, setPicked] = useState<string | null>(null);
  useEffect(() => {
    if (ui.dialog === "new-agent") setAgent(EMPTY_AGENT);
    if (ui.dialog === "resume") setPicked(null);
  }, [ui.dialog]);
  if (!ui.dialog) return null;
  const c = config[ui.dialog];
  const close = () => ui.set("dialog", null);
  const primary = () => {
    const store = daemonStore().getState();
    if (ui.dialog === "new-agent") {
      store.spawnWith({ name: agent.name.trim() || undefined, model: agent.model === INHERIT ? undefined : agent.model });
    } else if (ui.dialog === "resume" && picked) {
      store.spawnWith({ resume: picked, cwd: folder ?? undefined });
      ui.set("selectedAgentId", picked);
    }
    close();
  };
  const disabled = ui.dialog === "resume" && (!localMode || !picked);
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && close()}
      title={c.title}
      description={c.description}
      wide={c.wide}
      footer={
        <>
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
      {ui.dialog === "new-agent" && <NewAgentBody form={agent} onChange={setAgent} onSubmit={primary} />}
      {ui.dialog === "new-ticket" && <NewTicketBody />}
      {ui.dialog === "resume" &&
        (localMode ? (
          <ResumeBody folder={folder} onFolder={setFolder} picked={picked} onPick={setPicked} />
        ) : (
          <div className="text-sm text-muted-foreground">Resume runs in the embedded runner; this window is connected to a daemon.</div>
        ))}
    </AppDialog>
  );
}
