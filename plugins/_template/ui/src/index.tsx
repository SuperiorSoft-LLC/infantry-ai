// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The starter's web UI half (WEBUI-1): one centre tab, served by the daemon with the module and loaded by the app at run
// time, with no app rebuild. It draws with the app's own React and UI kit, which the app lends (they are not bundled).
// module.json `ui.contributes` declares the tab; activate() registers what it draws.
import { useState } from "react";
import { Rocket } from "lucide-react";
import { Button, PanelCard, TabPage } from "@infantry/ui";
import { definePlugin, UI_API_VERSION, useLinkState } from "@infantry/ui-plugin-api";
import "./index.css";

function Starter() {
  const link = useLinkState();
  const [n, setN] = useState(0);
  return (
    <TabPage title="Starter" description="A web UI half, drawn by the plugin.">
      <PanelCard title="Hello">
        <p className="text-sm text-muted-foreground">The daemon link is {link}.</p>
        <Button size="sm" onClick={() => setN(n + 1)}>
          Pressed {n} times
        </Button>
      </PanelCard>
    </TabPage>
  );
}

export default definePlugin({
  apiVersion: UI_API_VERSION,
  activate(ctx) {
    ctx.register("example.starter.web", { icon: Rocket, render: () => <Starter /> });
  },
});
