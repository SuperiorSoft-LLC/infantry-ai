// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Infantry_Desktop's web UI half (DESK-2): the `layout` view (the desktop) and a settings card. module.json `ui.contributes`
// declares both; the shell draws the layout when this device's `layout` preference names it (see the Desktop settings card).
import { LayoutDashboard } from "lucide-react";
import { definePlugin, UI_API_VERSION, type LayoutViewProps } from "@infantry/ui-plugin-api";
import { Desktop, DesktopSettings } from "./desktop";
import "./index.css";

export default definePlugin({
  apiVersion: UI_API_VERSION,
  activate(ctx) {
    ctx.register<LayoutViewProps>("infantry.desktop", { icon: LayoutDashboard, render: ({ Shell, View }) => <Desktop plugin={ctx.id} Shell={Shell} View={View} /> });
    ctx.register<{ plugin: string }>("infantry.desktop.config", { render: () => <DesktopSettings plugin={ctx.id} /> });
  },
});
