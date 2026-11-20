// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Infantry_Triggers' web UI half (WEBUI-1): the Triggers centre tab, served by the daemon with the module and loaded by
// the app at run time. module.json `ui.contributes` declares the tab (`infantry.triggers`); this registers what it draws.
import { Zap } from "lucide-react";
import { definePlugin, UI_API_VERSION } from "@infantry/ui-plugin-api";
import { bindTriggersStore, createTriggersStore } from "./store";
import { TriggersTab } from "./triggers-tab";
import "./index.css";

export default definePlugin({
  apiVersion: UI_API_VERSION,
  activate(ctx) {
    const store = createTriggersStore(ctx.link);
    bindTriggersStore(store);
    ctx.onDispose(() => {
      store.getState().dispose();
      bindTriggersStore(null);
    });
    ctx.register("infantry.triggers", { icon: Zap, render: () => <TriggersTab /> });
  },
});
