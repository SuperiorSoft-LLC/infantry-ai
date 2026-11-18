// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Round floating buttons over the transcript (FloatingChip.kt): expand, collapse, search, history.
import { Maximize2, Minus, Search, History } from "lucide-react";
import { IconButton } from "@infantry/ui";

const actions = [
  { label: "Expand all", icon: Maximize2 },
  { label: "Collapse all", icon: Minus },
  { label: "Search transcript", icon: Search },
  { label: "Session history", icon: History },
];

export function FloatingViewButtons() {
  return (
    <div className="pointer-events-none absolute right-5 bottom-3 flex gap-2">
      {actions.map(({ label, icon: Icon }) => (
        <IconButton
          key={label}
          label={label}
          variant="outline"
          size="icon-lg"
          className="pointer-events-auto rounded-full bg-card/90 shadow-md backdrop-blur"
        >
          <Icon />
        </IconButton>
      ))}
    </div>
  );
}
