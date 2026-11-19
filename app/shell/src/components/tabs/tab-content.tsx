// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Draws the active centre view through the renderer catalog (plugins/catalog.tsx). A view whose plugin went away shows
// an empty state, never a crash.
import { Puzzle } from "lucide-react";
import { EmptyState } from "@infantry/ui";
import type { CentreView } from "@/state/ui-state";
import { entryFor } from "@/plugins/catalog";
import { useRegistry } from "@/plugins/use-plugins";

export function TabContent({ tab }: { tab: CentreView }) {
  const reg = useRegistry();
  const entry = entryFor(tab, reg);
  if (!entry)
    return (
      <div className="grid flex-1 place-items-center p-6">
        <EmptyState icon={<Puzzle className="size-6" />} title="This view is not available" hint="The plugin that provides it is disabled or not installed." />
      </div>
    );
  return <>{entry.render()}</>;
}
