// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Max context field (the Kotlin ContextLimitField): a text box, 250k / 500k / 1M / off presets under it, and any
// value typed in (`300k`, `750000`). Shared by New agent and Modify session; one place, one wording.
import { CONTEXT_PRESETS, formatContextLimit, parseContextLimit } from "@infantry/client";
import { Button, Input } from "@infantry/ui";

export function ContextLimitField({ idPrefix, value, onChange, hint }: { idPrefix: string; value: string; onChange: (v: string) => void; hint?: string }) {
  const parsed = parseContextLimit(value);
  const bad = value.trim() !== "" && parsed == null;
  const presets = [...CONTEXT_PRESETS.map((p) => ({ label: p.label, text: p.label.toLowerCase() })), { label: "off", text: "off" }];
  return (
    <div className="space-y-1.5">
      <Input id={`${idPrefix}-context-limit`} aria-label="Max context" aria-invalid={bad} placeholder={hint ?? "default"} value={value} onChange={(e) => onChange(e.target.value)} />
      <div className="flex flex-wrap gap-1.5" role="group" aria-label="Max context presets">
        {presets.map((p) => (
          <Button key={p.label} type="button" size="sm" variant={value.trim() !== "" && parsed === parseContextLimit(p.text) ? "default" : "outline"} onClick={() => onChange(p.text)}>
            {p.label}
          </Button>
        ))}
        {value.trim() !== "" && (
          <Button type="button" size="sm" variant="ghost" onClick={() => onChange("")}>
            Use default
          </Button>
        )}
      </div>
      {bad && <p className="text-xs text-danger">Not a size: use a number of tokens, 250k, 1m or off.</p>}
      {!bad && parsed != null && (
        <p className="text-xs text-muted-foreground">{parsed === 0 ? "No cap: the harness's own window." : `${formatContextLimit(parsed)} tokens (${parsed.toLocaleString()}).`}</p>
      )}
    </div>
  );
}
