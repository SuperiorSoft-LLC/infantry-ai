// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Renders whichever dialog the UI state names, always through the one AppDialog.
import { useUi } from "@/state/ui-state";
import { Button } from "@infantry/ui";
import { AppDialog } from "@infantry/ui";
import { NewAgentBody, NewTicketBody } from "./create-bodies";
import { SettingsBody } from "./settings-body";

const config = {
  settings: { title: "Settings", description: "Appearance, daemon and notifications", primary: "Done", wide: true },
  "new-agent": { title: "New agent", description: "Only a name is needed. Everything else inherits.", primary: "Create agent", wide: false },
  "new-ticket": { title: "New ticket", description: "Only a title is needed. Everything else inherits.", primary: "Create ticket", wide: false },
} as const;

export function DialogHost() {
  const ui = useUi();
  if (!ui.dialog) return null;
  const c = config[ui.dialog];
  const close = () => ui.set("dialog", null);
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
          <Button onClick={close}>{c.primary}</Button>
        </>
      }
    >
      {ui.dialog === "settings" && <SettingsBody />}
      {ui.dialog === "new-agent" && <NewAgentBody />}
      {ui.dialog === "new-ticket" && <NewTicketBody />}
    </AppDialog>
  );
}
