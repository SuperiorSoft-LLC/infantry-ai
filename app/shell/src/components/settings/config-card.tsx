// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// One declared config group as a Settings card: a react-hook-form form whose zod schema is built from the field
// declarations (type, min/max, options, required, pattern). A valid change is written at once; an invalid one shows its
// error and writes nothing. A write the daemon refuses is rolled back by the store.
import { useEffect, useMemo } from "react";
import { Controller, useForm, type Resolver } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { RotateCcw } from "lucide-react";
import { z } from "zod";
import { resolved, settingKey, type ConfigField, type ConfigGroup } from "@infantry/client";
import { Button, Chip, Input, Select, SelectContent, SelectItem, SelectTrigger, SelectValue, Switch, ToggleGroup, ToggleGroupItem } from "@infantry/ui";
import { SettingRow, SettingsCard } from "./setting-card";
import { settingsStore, useSettings } from "./use-settings";

const slot = (i: number) => `f${i}`;

/** The zod type of one field. A dotted key is not a form path, so the form uses slots (`f0`, `f1`). */
function fieldSchema(f: ConfigField): z.ZodType {
  switch (f.type) {
    case "checkbox":
      return z.boolean();
    case "number": {
      let n = z.number("Enter a number");
      if (f.min != null) n = n.min(f.min, `At least ${f.min}`);
      if (f.max != null) n = n.max(f.max, `At most ${f.max}`);
      return z.preprocess((v) => (v === "" || v === null ? undefined : Number(v)), n);
    }
    case "select":
      return f.options?.length ? z.enum(f.options as [string, ...string[]]) : z.string();
    case "multiSelect":
      return z.array(z.string());
    case "text": {
      let s = z.string();
      if (f.required) s = s.min(1, "Required");
      if (f.pattern) s = s.regex(new RegExp(`^(?:${f.pattern})$`), f.patternMessage ?? "Does not match the expected format");
      return s;
    }
    default:
      return z.unknown();
  }
}

function formValue(f: ConfigField, v: unknown): unknown {
  if (f.type === "checkbox") return v === true;
  if (f.type === "multiSelect") return Array.isArray(v) ? v : [];
  if (f.type === "number") return v == null ? "" : String(v);
  if (f.type === "text" || f.type === "select") return v == null ? "" : String(v);
  return v;
}

function optionLabel(f: ConfigField, o: string): string {
  return f.optionLabels?.[o] ?? o;
}

export function ConfigCard({ group }: { group: ConfigGroup }) {
  const fields = useMemo(() => [...(group.fields ?? [])].sort((a, b) => (a.order ?? 0) - (b.order ?? 0)), [group.fields]);
  const values = useSettings((s) => s.values);
  const disabled = !!group.readOnly || !!group.locked;
  const schema = useMemo(() => z.object(Object.fromEntries(fields.map((f, i) => [slot(i), fieldSchema(f)]))), [fields]);
  const current = useMemo(() => Object.fromEntries(fields.map((f, i) => [slot(i), formValue(f, resolved(values, group.node, f))])), [fields, values, group.node]);
  const form = useForm<Record<string, unknown>>({ resolver: zodResolver(schema as never) as unknown as Resolver<Record<string, unknown>>, values: current, mode: "onChange" });
  useEffect(() => form.reset(current), [current]); // eslint-disable-line react-hooks/exhaustive-deps

  const commit = async (i: number, raw: unknown) => {
    const f = fields[i];
    form.setValue(slot(i), raw, { shouldValidate: true });
    if (!(await form.trigger(slot(i)))) return;
    const value = f.type === "number" ? Number(raw) : raw;
    if (value !== resolved(settingsStore().getState().values, group.node, f)) settingsStore().getState().set(group.node, f.key, value);
  };
  const reset = (f: ConfigField) => settingsStore().getState().set(group.node, f.key, null);

  return (
    <SettingsCard title={group.title ?? group.group ?? group.node} applies={group.applies}>
      {fields.length === 0 && <p className="py-1 text-xs text-muted-foreground">{group.block ? "Drawn by the plugin; open it from its own tab." : "Nothing to set."}</p>}
      {fields.map((f, i) => {
        const id = `cfg-${group.node}-${f.key}`;
        const overridden = settingKey(group.node, f.key) in values;
        const error = form.formState.errors[slot(i)]?.message as string | undefined;
        return (
          <SettingRow key={f.key} id={id} label={f.label ?? f.key} hint={f.description} error={error}>
            <Chip mono>{overridden ? (f.scope ?? "app") : "default"}</Chip>
            {overridden && !disabled && (
              <Button variant="ghost" size="icon" className="size-6" aria-label={`Reset ${f.label ?? f.key} to its default`} onClick={() => reset(f)}>
                <RotateCcw className="size-3" />
              </Button>
            )}
            <Controller
              control={form.control}
              name={slot(i)}
              render={({ field }) => {
                switch (f.type) {
                  case "checkbox":
                    return <Switch id={id} disabled={disabled} checked={field.value === true} onCheckedChange={(v) => void commit(i, v)} />;
                  case "select":
                    return (
                      <Select disabled={disabled} value={String(field.value ?? "")} onValueChange={(v) => void commit(i, v)}>
                        <SelectTrigger id={id} className="w-44">
                          <SelectValue />
                        </SelectTrigger>
                        <SelectContent>
                          {(f.options ?? []).map((o) => (
                            <SelectItem key={o} value={o}>
                              {optionLabel(f, o)}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    );
                  case "multiSelect":
                    return (
                      <ToggleGroup type="multiple" variant="outline" size="sm" disabled={disabled} value={(field.value as string[]) ?? []} onValueChange={(v) => void commit(i, v)}>
                        {(f.options ?? []).map((o) => (
                          <ToggleGroupItem key={o} value={o}>
                            {optionLabel(f, o)}
                          </ToggleGroupItem>
                        ))}
                      </ToggleGroup>
                    );
                  case "number":
                  case "text":
                    return (
                      <Input
                        id={id}
                        className="w-44"
                        type={f.type === "number" ? "number" : "text"}
                        min={f.min ?? undefined}
                        max={f.max ?? undefined}
                        step={f.step ?? undefined}
                        disabled={disabled}
                        aria-invalid={!!error}
                        value={String(field.value ?? "")}
                        onChange={(e) => form.setValue(slot(i), e.target.value, { shouldValidate: true })}
                        onBlur={(e) => void commit(i, e.target.value)}
                        onKeyDown={(e) => e.key === "Enter" && void commit(i, e.currentTarget.value)}
                      />
                    );
                  default:
                    return <span className="font-mono text-xs text-muted-foreground">{JSON.stringify(field.value)}</span>;
                }
              }}
            />
          </SettingRow>
        );
      })}
    </SettingsCard>
  );
}
