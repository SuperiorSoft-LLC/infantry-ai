// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The two usage dialogs, in the one AppDialog: type a percent by hand (when the live read is off or wrong), and
// override the pacing allowance for a while (blank or "none" = no limit).
import { useEffect, useState } from "react";
import { meterName, type MeterUsage } from "@infantry/client";
import { AppDialog, Button, Input, Label } from "@infantry/ui";
import { useUsage } from "./use-usage";

function Field({ id, label, hint, value, onChange, placeholder }: { id: string; label: string; hint?: string; value: string; onChange: (v: string) => void; placeholder?: string }) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label}</Label>
      <Input id={id} inputMode="decimal" placeholder={placeholder} value={value} onChange={(e) => onChange(e.target.value)} />
      {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
    </div>
  );
}

const parse = (s: string): number | null => {
  const t = s.trim().replace(/%$/, "");
  if (!t) return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : NaN;
};

export function TypePercentDialog({ meter, open, onClose }: { meter: MeterUsage; open: boolean; onClose: () => void }) {
  const typePercent = useUsage((s) => s.typePercent);
  const clearTyped = useUsage((s) => s.clearTyped);
  const [pct, setPct] = useState("");
  const [mins, setMins] = useState("");
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    if (open) (setPct(meter.percent != null ? String(Math.round(meter.percent)) : ""), setMins(""), setErr(null));
  }, [open, meter.percent]);
  const submit = () => {
    const p = parse(pct);
    const m = parse(mins);
    if (p == null || Number.isNaN(p) || p < 0 || p > 100) return setErr("Type a percent from 0 to 100.");
    if (Number.isNaN(m) || (m != null && m <= 0)) return setErr("Minutes until reset must be a positive number, or blank.");
    if (typePercent(meter.meter, p, m ?? undefined)) onClose();
  };
  return (
    <AppDialog
      open={open}
      onOpenChange={(o) => !o && onClose()}
      title={`Usage of ${meterName(meter.meter)}`}
      description="Pacing judges this percent until the window resets (or 5 hours without a reset time)."
      footer={
        <>
          {meter.source === "manual" && (
            <Button variant="ghost" className="mr-auto" onClick={() => (clearTyped(meter.meter), onClose())}>
              Clear typed value
            </Button>
          )}
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={submit}>Save</Button>
        </>
      }
    >
      <form className="space-y-4" onSubmit={(e) => (e.preventDefault(), submit())}>
        <Field id="usage-pct" label="Percent used" value={pct} onChange={setPct} placeholder="e.g. 42" />
        <Field id="usage-reset" label="Minutes until reset" hint="Optional." value={mins} onChange={setMins} />
        {err && (
          <p role="alert" className="text-xs text-danger">
            {err}
          </p>
        )}
      </form>
    </AppDialog>
  );
}

export function OverrideDialog({ meter, open, onClose }: { meter: MeterUsage; open: boolean; onClose: () => void }) {
  const override = useUsage((s) => s.override);
  const clearOverride = useUsage((s) => s.clearOverride);
  const [workers, setWorkers] = useState("");
  const [hours, setHours] = useState("");
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    if (open) (setWorkers(meter.override?.workers != null ? String(meter.override.workers) : ""), setHours(""), setErr(null));
  }, [open, meter.override]);
  const submit = () => {
    const w = workers.trim().toLowerCase() === "none" ? null : parse(workers);
    const h = parse(hours);
    if (Number.isNaN(w) || (w != null && (w < 0 || !Number.isInteger(w)))) return setErr("Workers must be a whole number, or blank / none for no limit.");
    if (Number.isNaN(h) || (h != null && h <= 0)) return setErr("Hours must be a positive number, or blank.");
    if (override(meter.meter, w, h ?? undefined)) onClose();
  };
  return (
    <AppDialog
      open={open}
      onOpenChange={(o) => !o && onClose()}
      title={`Override ${meterName(meter.meter)} pacing`}
      description={`The pacing rule allows ${meter.ruleAllowance ?? "any number of"} ticket workers now. An override replaces it until it ends.`}
      footer={
        <>
          {meter.override && (
            <Button variant="ghost" className="mr-auto" onClick={() => (clearOverride(meter.meter), onClose())}>
              Clear override
            </Button>
          )}
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={submit}>Override</Button>
        </>
      }
    >
      <form className="space-y-4" onSubmit={(e) => (e.preventDefault(), submit())}>
        <Field id="ovr-workers" label="Ticket workers allowed" hint="Blank or none = no limit." value={workers} onChange={setWorkers} placeholder="none" />
        <Field id="ovr-hours" label="For how many hours" hint="Blank = until the window resets (else 2 hours)." value={hours} onChange={setHours} />
        {err && (
          <p role="alert" className="text-xs text-danger">
            {err}
          </p>
        )}
      </form>
    </AppDialog>
  );
}
