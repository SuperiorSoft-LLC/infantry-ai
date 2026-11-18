// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Create forms: one title field, then pickers under a collapsed Advanced (New agent: new-agent-dialog.tsx).
import { Input } from "@infantry/ui";
import { Label } from "@infantry/ui";
import { projects, roles } from "@/mock/data";
import { AdvancedSection } from "./advanced-section";
import { PickerField } from "./picker-field";

export function NewTicketBody() {
  return (
    <div className="space-y-4">
      <div className="space-y-1.5">
        <Label htmlFor="ticket-title">Title</Label>
        <Input id="ticket-title" placeholder="What needs doing?" autoFocus />
      </div>
      <AdvancedSection>
        <PickerField id="ticket-project" label="Project" options={projects.map((p) => p.name)} inheritLabel="Current project" />
        <PickerField id="ticket-role" label="Role" options={roles.map((r) => r.label)} />
        <PickerField id="ticket-epic" label="Epic" options={["Rust Port", "Calendar", "Hosting"]} inheritLabel="No epic" />
        <PickerField id="ticket-branch" label="Base branch" options={["dev", "staging", "main"]} />
      </AdvancedSection>
    </div>
  );
}
