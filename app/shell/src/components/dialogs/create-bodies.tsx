// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Create forms: one name/title field, then pickers under a collapsed Advanced.
import { Input } from "@infantry/ui";
import { Label } from "@infantry/ui";
import { projects, roles } from "@/mock/data";
import { AdvancedSection } from "./advanced-section";
import { INHERIT, PickerField } from "./picker-field";

/** What the New agent form holds (the dialog host owns it, so its primary button can act on it). */
export interface NewAgentForm {
  name: string;
  model: string;
}
export const EMPTY_AGENT: NewAgentForm = { name: "", model: INHERIT };

const harnesses = ["claude", "opencode", "pi"];
const models = ["opus", "sonnet", "haiku", "deepseek-v4"];
const runners = ["This computer", "vps-runner-01"];

export function NewAgentBody({ form, onChange, onSubmit }: { form?: NewAgentForm; onChange?: (f: NewAgentForm) => void; onSubmit?: () => void }) {
  return (
    <div className="space-y-4">
      <div className="space-y-1.5">
        <Label htmlFor="agent-name">Name</Label>
        <Input
          id="agent-name"
          placeholder="e.g. Backend"
          autoFocus
          value={form?.name}
          onChange={(e) => form && onChange?.({ ...form, name: e.target.value })}
          onKeyDown={(e) => e.key === "Enter" && onSubmit?.()}
        />
      </div>
      <AdvancedSection>
        <PickerField id="agent-role" label="Role" options={roles.map((r) => r.label)} />
        <PickerField id="agent-harness" label="Harness" options={harnesses} />
        <PickerField id="agent-model" label="Model" options={models} value={form?.model} onChange={(m) => form && onChange?.({ ...form, model: m })} />
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
