// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Test plugin (WEBUI-1), never shipped: rust/plugins/stage.sh stages rust/plugins/test only with INFANTRY_STAGE_TEST=1.
// Its centre tab throws while drawing. The app must show its fault card in that tab, with Retry, and every other tab
// must keep working.
import { Bug } from "lucide-react";
import { definePlugin, UI_API_VERSION } from "@infantry/ui-plugin-api";

function Broken(): never {
  throw new Error("Acme_UiFault throws on purpose while drawing (a test plugin)");
}

export default definePlugin({
  apiVersion: UI_API_VERSION,
  activate(ctx) {
    ctx.register("acme.uifault", { icon: Bug, render: () => <Broken /> });
  },
});
