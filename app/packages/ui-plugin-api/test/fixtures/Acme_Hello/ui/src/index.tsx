// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Test fixture: the smallest web UI half, a centre tab with a counter.
import { useState } from "react";
import { Sparkles } from "lucide-react";
import { Button, TabPage } from "@infantry/ui";
import { definePlugin, UI_API_VERSION } from "@infantry/ui-plugin-api";
import "./index.css";

function Hello() {
  const [n, setN] = useState(0);
  return (
    <TabPage title="Hello">
      <Button className="text-danger" onClick={() => setN(n + 1)}>
        Pressed {n} times
      </Button>
    </TabPage>
  );
}

export default definePlugin({
  apiVersion: UI_API_VERSION,
  activate(ctx) {
    ctx.register("acme.hello", { icon: Sparkles, render: () => <Hello /> });
  },
});
