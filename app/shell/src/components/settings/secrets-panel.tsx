// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Settings > Secrets: the daemon vault's secrets, names and fields only. Add / edit a field (the value goes in once and
// never comes back), Reveal and Copy after Windows Hello (the value lives in this component a few seconds, never in a
// store), delete. Reuses the bordered row cards, Button and Input of the kit.
import { useEffect, useRef, useState } from "react";
import { useStore } from "zustand";
import { createSecretsStore, placeholderOf, secretId, type SecretEntryRow, type SecretsStore } from "@infantry/client";
import { Button, Input, Label } from "@infantry/ui";
import { daemonStore } from "@/daemon/daemon";

let store: SecretsStore | null = null;
function secretsStore(): SecretsStore {
  return (store ??= createSecretsStore(daemonStore().getState()));
}

function FieldRow({ entry, field, tail }: { entry: SecretEntryRow; field: string; tail: string }) {
  const s = secretsStore().getState();
  const [shown, setShown] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout>>();
  useEffect(() => () => clearTimeout(timer.current), []);
  const ask = async (purpose: "reveal" | "copy") => {
    setNote("Waiting for Windows Hello...");
    try {
      const r = await s.reveal(entry.name, field, purpose, entry.project);
      if (purpose === "reveal") {
        setShown(r.value);
        setNote(null);
        clearTimeout(timer.current);
        timer.current = setTimeout(() => setShown(null), r.showSeconds * 1000);
      } else {
        await navigator.clipboard.writeText(r.value);
        setNote(`Copied; cleared in ${r.clearSeconds}s`);
        const copied = r.value;
        setTimeout(() => {
          void navigator.clipboard.readText().then((now) => now === copied && navigator.clipboard.writeText(""), () => {});
          setNote(null);
        }, r.clearSeconds * 1000);
      }
    } catch (why) {
      setNote(String(why));
    }
  };
  return (
    <div className="flex items-center gap-2 text-[13px]">
      <span className="w-40 truncate font-mono">{field}</span>
      <span className="min-w-0 flex-1 truncate font-mono text-muted-foreground">{shown ?? tail}</span>
      <span className="hidden truncate font-mono text-xs text-muted-foreground sm:inline">{placeholderOf(entry, field)}</span>
      <Button size="sm" variant="ghost" onClick={() => (shown ? setShown(null) : void ask("reveal"))}>
        {shown ? "Hide" : "Reveal"}
      </Button>
      <Button size="sm" variant="ghost" onClick={() => void ask("copy")}>
        Copy
      </Button>
      <Button size="sm" variant="ghost" onClick={() => void s.deleteField(entry.name, field, entry.project).catch(() => {})}>
        Delete
      </Button>
      {note && <span className="text-xs text-muted-foreground">{note}</span>}
    </div>
  );
}

function AddField({ name: initial }: { name?: string }) {
  const [name, setName] = useState(initial ?? "");
  const [field, setField] = useState(initial ? "" : "value");
  const [value, setValue] = useState("");
  const save = () => {
    if (!name.trim() || !field.trim() || !value) return;
    void secretsStore()
      .getState()
      .setField(name.trim(), field.trim(), value)
      .then(() => setValue(""), () => {});
  };
  return (
    <div className="flex flex-wrap items-end gap-2">
      {!initial && (
        <div className="grid gap-1">
          <Label htmlFor="sec-name">Secret</Label>
          <Input id="sec-name" value={name} onChange={(e) => setName(e.target.value)} placeholder="stripe" className="w-40" />
        </div>
      )}
      <div className="grid gap-1">
        <Label htmlFor={`sec-field-${initial ?? ""}`}>Field</Label>
        <Input id={`sec-field-${initial ?? ""}`} value={field} onChange={(e) => setField(e.target.value)} placeholder="SECRET_KEY" className="w-40" />
      </div>
      <div className="grid min-w-40 flex-1 gap-1">
        <Label htmlFor={`sec-value-${initial ?? ""}`}>Value</Label>
        <Input id={`sec-value-${initial ?? ""}`} type="password" autoComplete="off" value={value} onChange={(e) => setValue(e.target.value)} />
      </div>
      <Button size="sm" onClick={save}>
        {initial ? "Set field" : "Add"}
      </Button>
    </div>
  );
}

export function SecretsPanel() {
  const st = useStore(secretsStore());
  useEffect(() => st.ensureLoaded(), [st]);
  return (
    <div className="space-y-2">
      <div className="rounded-lg border bg-card p-3 text-xs text-muted-foreground">
        Protected by {st.protection || "..."}. Values never leave the daemon except Reveal and Copy, which ask Windows Hello first.
        Agents use placeholders such as <span className="font-mono">{"{{secret:NAME#FIELD}}"}</span>.
        {st.locked && <span className="ml-1 font-medium text-foreground">Locked by a restricted policy.</span>}
      </div>
      {st.refusal && (
        <div className="rounded-lg border bg-card p-3 text-xs" onClick={st.clearRefusal}>
          {st.refusal}
        </div>
      )}
      {st.entries.map((e) => (
        <div key={secretId(e)} className="space-y-2 rounded-lg border bg-card p-3">
          <div className="flex items-center gap-2">
            <span className="font-medium">{e.name}</span>
            <span className="text-xs text-muted-foreground">{e.project ? `project ${e.project}` : "app"}</span>
            <span className="flex-1" />
            <Button size="sm" variant="ghost" onClick={() => void st.deleteSecret(e.name, e.project).catch(() => {})}>
              Delete secret
            </Button>
          </div>
          {e.fields.map((f) => (
            <FieldRow key={f.name} entry={e} field={f.name} tail={f.tail} />
          ))}
          {!e.project && <AddField name={e.name} />}
        </div>
      ))}
      <div className="rounded-lg border bg-card p-3">
        <AddField />
      </div>
    </div>
  );
}
