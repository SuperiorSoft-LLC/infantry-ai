// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Create forms: one name/title field, then pickers under a collapsed Advanced.
import { Input } from "@infantry/ui";
import { Label } from "@infantry/ui";
import { projects, roles } from "@/mock/data";
import { AdvancedSection } from "./advanced-section";
import { PickerField } from "./picker-field";

const harnesses = ["claude", "opencode", "pi"];
const models = ["opus", "sonnet", "haiku", "deepseek-v4"];
const runners = ["This computer", "vps-runner-01"];

export function NewAgentBody() {
  return (
    <div className="space-y-4">
      <div className="space-y-1.5">
        <Label htmlFor="agent-name">Name</Label>
        <Input id="agent-name" placeholder="e.g. Backend" autoFocus />
      </div>
      <AdvancedSection>
        <PickerField id="agent-role" label="Role" options={roles.map((r) => r.label)} />
        <PickerField id="agent-harness" label="Harness" options={harnesses} />
        <PickerField id="agent-model" label="Model" options={models} />
        <PickerField id="agent-runner" label="Runner" options={runners} />
      </AdvancedSection>
    </div>
  );
}

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
