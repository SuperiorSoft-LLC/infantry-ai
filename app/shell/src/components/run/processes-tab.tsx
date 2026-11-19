// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Processes tab (RUST-UI-5): the daemon's process report on its own page (the same panel the Run tab shows).
import { RefreshCw } from "lucide-react";
import { Button } from "@infantry/ui";
import { TabPage } from "../tabs/tab-page";
import { RefusalBanner } from "../knowledge/parts";
import { ProcessesPanel } from "./run-tab";
import { runsStore, useRuns } from "./use-runs";

export function ProcessesTab() {
  const refusal = useRuns((s) => s.refusal);
  return (
    <TabPage
      title="Processes"
      description="Every process the daemon tracks, and the ones it cannot attribute"
      actions={
        <Button variant="ghost" size="sm" aria-label="Refresh" onClick={() => runsStore().getState().listProcesses()}>
          <RefreshCw />
        </Button>
      }
    >
      <RefusalBanner text={refusal} onDismiss={() => runsStore().getState().clearRefusal()} />
      <ProcessesPanel />
    </TabPage>
  );
}
