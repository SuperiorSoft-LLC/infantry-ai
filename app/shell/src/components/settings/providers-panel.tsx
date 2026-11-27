// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Settings > Providers (659ca918): every harness the runner declares a credential for (sign-in and/or API key), shown
// with the SAME component as the setup wizard's sign-in step, so setup can be checked and changed at any time.
// Data-driven: the list comes from the runner's credential rows, never a provider list in the app.
import { useEffect, useState } from "react";
import { useStore } from "zustand";
import { runnerStores } from "@/daemon/runner-stores";
import { useLauncher } from "@/components/dialogs/new-agent-dialog";
import { RunnerPicker } from "@/components/dialogs/executor-picker";
import { ProviderSetup, providerIds } from "@/components/setup/provider-setup";

function labelOf(id: string, rows: { name: string; label: string }[]): string {
  const r = rows.find((x) => x.name === `harness.${id}.token`) ?? rows.find((x) => x.name === `harness.${id}.key`);
  return r ? r.label.replace(/ (subscription token|API key)$/i, "") : id;
}

export function ProvidersPanel() {
  const [runner, setRunner] = useState("local");
  const { runners } = useLauncher();
  const st = runnerStores(runner);
  const sec = useStore(st.secrets);
  useEffect(() => sec.ensureLoaded(), [sec]);
  const ids = providerIds(sec.builtIns);
  const recheck = () => {
    void st.secrets.getState().refresh();
    st.providers.getState().refresh();
  };
  return (
    <div className="space-y-2" data-testid="providers-panel">
      {runners.length > 0 && <RunnerPicker idPrefix="providers" machine value={runner} runners={runners} onChange={(r) => setRunner(r ?? "local")} />}
      <div className="rounded-lg border bg-card p-3 text-xs text-muted-foreground">
        The agent tools on {runner === "local" ? "this computer" : `runner ${runner}`} and how each one connects. The same setup as the guided setup; keys go in the vault and reach the agent when it starts.
      </div>
      {sec.refusal && (
        <div role="alert" className="cursor-pointer rounded-lg border border-danger/60 bg-danger/10 p-3 text-xs text-danger" onClick={sec.clearRefusal}>
          {sec.refusal}
        </div>
      )}
      {!sec.loaded && !sec.refusal && <p className="text-xs text-muted-foreground">Loading...</p>}
      {sec.loaded && ids.length === 0 && <p className="rounded-lg border bg-card p-3 text-xs text-muted-foreground">No agent tool declares a sign-in or key here yet. The guided setup installs the tools.</p>}
      {ids.map((id) => (
        <div key={id} className="rounded-lg border bg-card p-3">
          <ProviderSetup id={id} label={labelOf(id, sec.builtIns)} rows={sec.builtIns} recheck={recheck} st={st} named />
        </div>
      ))}
    </div>
  );
}
