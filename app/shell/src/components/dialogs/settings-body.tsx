// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Settings dialog body: a side nav of sections and bordered setting rows.
import { useEffect, useState } from "react";
import { host } from "@/host";
import { RemoteRow } from "@/components/connect/connect-screen";
import { CliPathRow, PairingRows, UpdaterRows } from "@/components/settings/desktop-install";
import { cn } from "@infantry/ui";
import { useUi } from "@/state/ui-state";
import { Button, Switch } from "@infantry/ui";
import { Label } from "@infantry/ui";
import { appVersionLine } from "@/components/layout/app-version";
import { SecretsPanel } from "@/components/settings/secrets-panel";
import { DeclaredSettings, SettingsSearch, useDeclaredTabs } from "@/components/settings/declared-settings";

const sections = ["Appearance", "Daemon", "Secrets", "About"] as const;

function SettingRow({ id, title, hint, children }: { id: string; title: string; hint: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-4 rounded-lg border bg-card p-3">
      <div className="min-w-0">
        <Label htmlFor={id}>{title}</Label>
        <p className="text-xs text-muted-foreground">{hint}</p>
      </div>
      {children}
    </div>
  );
}

/** Settings > Daemon: where agents run (`ui.json` `runner`, read at start). Only where the app has an embedded runner (desktop). */
function RunnerRow() {
  const [runner, setRunner] = useState<{ saved: string; current: string } | null>(null);
  useEffect(() => {
    if (!host.embedded) return;
    void host.call<[string, string]>("ui_runner_get").then(([saved, current]) => setRunner({ saved, current }), () => {});
  }, []);
  if (!runner) return null;
  const embedded = runner.saved === "embedded";
  const change = (on: boolean) => {
    const next = on ? "embedded" : "daemon";
    void host.call("ui_runner_set", { runner: next }).then(() => setRunner({ ...runner, saved: next }), () => {});
  };
  const hint = `Run agents inside this window instead of the infantry daemon. Now: ${runner.current}${runner.saved !== runner.current && runner.current !== "connect" ? " (restart to apply)" : ""}`;
  return (
    <SettingRow id="set-embedded" title="Embedded runner" hint={hint}>
      <Switch id="set-embedded" checked={embedded} onCheckedChange={change} />
    </SettingRow>
  );
}

export function SettingsBody() {
  const ui = useUi();
  const [section, setSection] = useState<string>("Appearance");
  const [query, setQuery] = useState("");
  const declared = useDeclaredTabs().filter((t) => !(sections as readonly string[]).includes(t));
  const searching = query.trim() !== "";
  return (
    <div className="grid gap-4 sm:grid-cols-[160px_minmax(0,1fr)]">
      <nav aria-label="Settings sections" className="flex gap-1 overflow-x-auto sm:sticky sm:top-0 sm:flex-col sm:self-start">
        {[...sections, ...declared].map((s) => (
          <button
            key={s}
            type="button"
            onClick={() => setSection(s)}
            className={cn(
              "rounded-md px-3 py-1.5 text-left text-[13px] whitespace-nowrap",
              s === section && !searching ? "bg-accent font-medium text-foreground" : "text-muted-foreground hover:text-foreground",
            )}
          >
            {s}
          </button>
        ))}
      </nav>
      <div className="min-w-0 space-y-2">
        <SettingsSearch query={query} onQuery={setQuery} />
        {(searching || declared.includes(section)) && <DeclaredSettings tab={section} query={query} />}
        {!searching && section === "Appearance" && (
          <>
            <SettingRow id="set-workers" title="Global workers board" hint="The Working counter opens every project's working agents">
              <Switch id="set-workers" checked={ui.globalWorkers} onCheckedChange={(v) => ui.set("globalWorkers", v)} />
            </SettingRow>
            <SettingRow id="set-dark" title="Dark theme" hint="Light theme when off">
              <Switch id="set-dark" checked={ui.theme === "dark"} onCheckedChange={ui.toggleTheme} />
            </SettingRow>
          </>
        )}
        {!searching && section === "Daemon" && (
          <>
            <RemoteRow />
            <PairingRows />
            <RunnerRow />
            <CliPathRow />
            <SettingRow id="set-setup" title="Guided setup" hint="Install an agent tool, sign in and say hello. It opens by itself while nothing can answer.">
              <Button size="sm" variant="outline" id="set-setup" onClick={() => ui.set("dialog", "setup")}>
                Open guided setup
              </Button>
            </SettingRow>
          </>
        )}
        {!searching && section === "Secrets" && <SecretsPanel />}
        {!searching && section === "About" && (
          <div className="rounded-lg border bg-card p-3 text-[13px]">
            <p className="font-semibold">Infantry AI</p>
            <p className="font-mono text-xs text-muted-foreground">{appVersionLine()}</p>
          </div>
        )}
        {!searching && section === "About" && <UpdaterRows />}
      </div>
    </div>
  );
}
