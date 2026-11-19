// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Calendar: shown only when a plugin declares `infantry.calendar`. The Rust daemon has no calendar frames yet, so the
// tab says so instead of drawing sample data (docs/rust-port/PARITY.md).
import { Calendar } from "lucide-react";
import { EmptyState } from "@infantry/ui";
import { TabPage } from "./tab-page";

export function CalendarTab() {
  return (
    <TabPage title="Calendar" description="Tasks by day">
      <EmptyState icon={<Calendar className="size-6" />} title="Not in the Rust daemon yet" hint="The calendar needs its daemon frames (calendar list, add, complete) before this tab can show tasks." />
    </TabPage>
  );
}
