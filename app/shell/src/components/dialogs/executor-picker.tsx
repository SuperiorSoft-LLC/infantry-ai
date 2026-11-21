// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The one executor picker: Harness -> Provider -> Model -> Effort dropdowns filled from the daemon's harness
// configurations (the `providers` and `models` frames). Used by the New agent form and the Switch provider/model dialog.
// Only valid combinations: the provider and model lists follow the harness; "default" stays an option.
import { modelsFor, unavailableLine, type HarnessInfo, type ModelGroup } from "@infantry/client";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@infantry/ui";
import { Field } from "@/components/knowledge/parts";

export interface ExecutorValue {
  harness: string | null;
  group: string | null;
  model: string | null;
  effort: string | null;
}

const DEFAULT = "__default__";
const EFFORTS = ["low", "medium", "high", "xhigh", "max"];

/** The value after picking [harness]: its default model, a clean provider, no effort carried over. */
export function pickHarness(v: ExecutorValue, harness: string, harnesses: HarnessInfo[]): ExecutorValue {
  const h = harnesses.find((x) => x.id === harness);
  return { ...v, harness, group: null, model: h?.defaultModel ?? null, effort: v.effort };
}

export function ExecutorPicker({
  idPrefix,
  value,
  harnesses,
  catalog,
  onChange,
}: {
  idPrefix: string;
  value: ExecutorValue;
  harnesses: HarnessInfo[];
  catalog: ModelGroup[];
  onChange: (v: ExecutorValue) => void;
}) {
  const groups = modelsFor(value.harness, harnesses, catalog);
  const shown = groups.filter((g) => !value.group || g.group === value.group);
  const models = [...new Set(shown.flatMap((g) => g.models))];
  const efforts = harnesses.find((h) => h.id === value.harness)?.efforts ?? EFFORTS;
  return (
    <div className="space-y-3">
      <Field id={`${idPrefix}-harness`} label="Harness" hint={unavailableLine(harnesses) ?? undefined}>
        <Select value={value.harness ?? undefined} onValueChange={(v) => onChange(pickHarness(value, v, harnesses))}>
          <SelectTrigger id={`${idPrefix}-harness`} className="w-full">
            <SelectValue placeholder={harnesses.length ? "Pick a harness" : "Loading harnesses..."} />
          </SelectTrigger>
          <SelectContent>
            {harnesses.map((h) => (
              <SelectItem key={h.id} value={h.id} disabled={h.available === false}>
                {h.id}
                {h.available === false ? " (unavailable)" : ""}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </Field>
      {groups.length > 1 && (
        <Field id={`${idPrefix}-provider`} label="Provider">
          <Select value={value.group ?? DEFAULT} onValueChange={(v) => onChange({ ...value, group: v === DEFAULT ? null : v, model: null })}>
            <SelectTrigger id={`${idPrefix}-provider`} className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={DEFAULT}>All providers</SelectItem>
              {groups.map((g) => (
                <SelectItem key={g.group} value={g.group}>
                  {g.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Field>
      )}
      <Field id={`${idPrefix}-model`} label="Model">
        <Select
          value={value.model ?? DEFAULT}
          onValueChange={(v) => onChange({ ...value, model: v === DEFAULT ? null : v, group: v === DEFAULT ? value.group : (groups.find((g) => g.models.includes(v))?.group ?? value.group) })}
          disabled={!value.harness}
        >
          <SelectTrigger id={`${idPrefix}-model`} className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={DEFAULT}>default</SelectItem>
            {models.map((m) => (
              <SelectItem key={m} value={m}>
                {m}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </Field>
      <Field id={`${idPrefix}-effort`} label="Effort">
        <Select value={value.effort ?? DEFAULT} onValueChange={(v) => onChange({ ...value, effort: v === DEFAULT ? null : v })} disabled={!value.harness}>
          <SelectTrigger id={`${idPrefix}-effort`} className="w-full">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={DEFAULT}>default</SelectItem>
            {efforts.map((e) => (
              <SelectItem key={e} value={e}>
                {e}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </Field>
    </div>
  );
}
