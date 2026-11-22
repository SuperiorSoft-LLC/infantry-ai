// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Infantry_Runners' web UI half (RR-9): the Runners card in Settings (`settings.card`, tab "Runners"). module.json
// `ui.contributes` declares it (`infantry.runners`); this registers what it draws.
import { definePlugin, UI_API_VERSION } from "@infantry/ui-plugin-api";
import { bindRunnersStore, createRunnersStore } from "./store";
import { RunnersCard } from "./runners-card";
import "./index.css";

export default definePlugin({
  apiVersion: UI_API_VERSION,
  activate(ctx) {
    const store = createRunnersStore(ctx.link);
    bindRunnersStore(store);
    ctx.onDispose(() => {
      store.getState().dispose();
      bindRunnersStore(null);
    });
    ctx.register("infantry.runners", { render: () => <RunnersCard /> });
  },
});
