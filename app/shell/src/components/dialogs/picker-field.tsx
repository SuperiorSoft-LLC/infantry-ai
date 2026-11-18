// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// A labelled picker filled from known values, with an inherit default that stores nothing.
// HARD RULE: no free-text box for a value the app can know.
import { Label } from "@infantry/ui";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@infantry/ui";

export const INHERIT = "__inherit__";

export function PickerField({
  id, label, options, inheritLabel = "Project default", value, onChange, labels,
}: { id: string; label: string; options: string[]; inheritLabel?: string; value?: string; onChange?: (v: string) => void; labels?: Record<string, string> }) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label}</Label>
      <Select defaultValue={value === undefined ? INHERIT : undefined} value={value} onValueChange={onChange}>
        <SelectTrigger id={id} className="w-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          <SelectItem value={INHERIT}>{inheritLabel}</SelectItem>
          {options.map((o) => (
            <SelectItem key={o} value={o}>
              {labels?.[o] ?? o}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
