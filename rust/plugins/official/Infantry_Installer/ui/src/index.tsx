// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Infantry_Installer's web UI half (INSTALLER-1): the Apps tab, served by the daemon with the module. module.json
// `ui.contributes` declares it (`infantry.installer`); this registers what it draws. The shell also draws this one view as
// the whole window while the daemon reports that nothing but the installer is installed.
import { PackageOpen } from "lucide-react";
import { definePlugin, UI_API_VERSION } from "@infantry/ui-plugin-api";
import { bindInstallerStore, createInstallerStore } from "./store";
import { InstallerTab } from "./installer-tab";
import "./index.css";

export default definePlugin({
  apiVersion: UI_API_VERSION,
  activate(ctx) {
    const store = createInstallerStore(ctx.link);
    bindInstallerStore(store);
    ctx.onDispose(() => {
      store.getState().dispose();
      bindInstallerStore(null);
    });
    ctx.register<{ route?: string }>("infantry.installer", { icon: PackageOpen, render: ({ route }) => <InstallerTab route={route} /> });
  },
});
