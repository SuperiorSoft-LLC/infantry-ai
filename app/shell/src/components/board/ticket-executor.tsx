// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #7: who runs a ticket, in the ticket dialog. Role, provider (harness) + model (the launcher's harness
// and model lists), weight 1-5 and effort, each a picker whose inherit choice stores nothing, plus the daemon's
// effective executor and where each part comes from (ticket, role, rule, weight, profile, default). The values ride the
// dialog's own save (`ticket_save`, what `infantry_ticket_update` builds); a cleared pin sends "" (weight 0).
import { modelsFor, roleList, type BoardTicket, type EffectiveExecutor, type HarnessInfo, type ModelGroup, type Role, type TicketDraft } from "@infantry/client";
import { Label } from "@infantry/ui";
import { INHERIT, PickerField } from "@/components/dialogs/picker-field";

const EFFORTS = ["low", "medium", "high", "xhigh", "max"];
const WEIGHTS = ["1", "2", "3", "4", "5"];

export interface ExecutorForm {
  agentId: string;
  provider: string;
  model: string;
  weight: string;
  effort: string;
}

/** The form values for [t]: each unset pin is INHERIT. */
export function executorForm(t: BoardTicket | undefined): ExecutorForm {
  return {
    agentId: t?.agentId || INHERIT,
    provider: t?.provider || INHERIT,
    model: t?.model || INHERIT,
    weight: t?.weight ? String(t.weight) : INHERIT,
    effort: t?.effort || INHERIT,
  };
}

/** The save fields for [v]: a pin that changed is sent; a pin cleared on a ticket that had one sends "" (weight 0); nothing else is sent. */
export function executorDraft(t: BoardTicket | undefined, v: ExecutorForm): Partial<TicketDraft> {
  const was = executorForm(t);
  const out: Partial<TicketDraft> = {};
  const str = (k: "agentId" | "provider" | "model" | "effort") => {
    if (v[k] === was[k]) return;
    out[k] = v[k] === INHERIT ? "" : v[k];
  };
  str("agentId");
  str("provider");
  str("model");
  str("effort");
  if (v.weight !== was.weight) out.weight = v.weight === INHERIT ? 0 : Number(v.weight);
  return out;
}

/** "role frontend · provider claude (ticket) · model sonnet (rule: cheap)": the effective executor, one line. */
export function effectiveLine(e: EffectiveExecutor | null | undefined): string | null {
  if (!e) return null;
  const part = (name: string, f: { value?: string | null; source: string } | null | undefined) =>
    f ? `${name} ${f.value ?? "default"} (${f.source === "rule" && e.rule ? `rule: ${e.rule}` : f.source})` : null;
  const who = e.role ? `role ${e.role}` : e.agentId ? `agent ${e.agentId}` : null;
  return [who, part("provider", e.provider), part("model", e.model)].filter(Boolean).join(" · ") || null;
}

export function TicketExecutor({
  value,
  onChange,
  roles,
  harnesses,
  catalog,
  effective,
}: {
  value: ExecutorForm;
  onChange: (v: ExecutorForm) => void;
  roles: Record<string, Role>;
  harnesses: HarnessInfo[];
  catalog: ModelGroup[];
  effective?: EffectiveExecutor | null;
}) {
  const provider = value.provider === INHERIT ? null : value.provider;
  const roleOpts = roleList(roles).map((r) => `role:${r.id}`);
  const roleIds = value.agentId !== INHERIT && !roleOpts.includes(value.agentId) ? [...roleOpts, value.agentId] : roleOpts;
  const roleNames = Object.fromEntries(roleList(roles).map((r) => [`role:${r.id}`, r.name]));
  const providers = [...new Set([...harnesses.map((h) => h.id), ...(provider ? [provider] : [])])];
  const models = [...new Set([...modelsFor(provider, harnesses, catalog).flatMap((g) => g.models), ...(value.model !== INHERIT ? [value.model] : [])])];
  const efforts = harnesses.find((h) => h.id === provider)?.efforts ?? EFFORTS;
  const line = effectiveLine(effective);
  const set = (k: keyof ExecutorForm) => (v: string) => onChange(k === "provider" ? { ...value, provider: v, model: INHERIT } : { ...value, [k]: v });
  return (
    <div className="space-y-3 rounded-md border p-3" aria-label="Executor">
      <Label>Executor</Label>
      <p className="text-xs text-muted-foreground" data-testid="effective-executor">
        {line ? `Runs as: ${line}` : "Runs as: the project default"}
      </p>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <PickerField id="ticket-role" label="Role" options={roleIds} labels={roleNames} value={value.agentId} onChange={set("agentId")} />
        <PickerField id="ticket-weight" label="Weight" options={WEIGHTS} inheritLabel="Unweighted" value={value.weight} onChange={set("weight")} />
        <PickerField id="ticket-provider" label="Provider" options={providers} value={value.provider} onChange={set("provider")} />
        <PickerField id="ticket-model" label="Model" options={models} value={value.model} onChange={set("model")} />
        <PickerField id="ticket-effort" label="Effort" options={efforts} value={value.effort} onChange={set("effort")} />
      </div>
    </div>
  );
}
