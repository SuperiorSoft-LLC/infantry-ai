// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Settings dialog body: a side nav of sections and bordered setting rows.
import { useEffect, useState } from "react";
import { isTauri } from "@/daemon/daemon";
import { cn } from "@infantry/ui";
import { useUi } from "@/state/ui-state";
import { Switch } from "@infantry/ui";
import { Label } from "@infantry/ui";
import { PickerField } from "./picker-field";

const sections = ["Appearance", "Daemon", "Notifications", "Voice", "About"] as const;

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

/** Settings > Daemon: where agents run (`ui.json` `runner`, read at start). Tauri only. */
function RunnerRow() {
  const [runner, setRunner] = useState<{ saved: string; current: string } | null>(null);
  useEffect(() => {
    if (!isTauri) return;
    void import("@tauri-apps/api/core").then(({ invoke }) =>
      invoke<[string, string]>("ui_runner_get").then(([saved, current]) => setRunner({ saved, current }), () => {}),
    );
  }, []);
  if (!runner) return null;
  const embedded = runner.saved === "embedded";
  const change = (on: boolean) => {
    const next = on ? "embedded" : "daemon";
    void import("@tauri-apps/api/core").then(({ invoke }) =>
      invoke("ui_runner_set", { runner: next }).then(() => setRunner({ ...runner, saved: next }), () => {}),
    );
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
  const [section, setSection] = useState<(typeof sections)[number]>("Appearance");
  return (
    <div className="grid gap-4 sm:grid-cols-[160px_1fr]">
      <nav aria-label="Settings sections" className="flex gap-1 overflow-x-auto sm:flex-col">
        {sections.map((s) => (
          <button
            key={s}
            type="button"
            onClick={() => setSection(s)}
            className={cn(
              "rounded-md px-3 py-1.5 text-left text-[13px] whitespace-nowrap",
              s === section ? "bg-accent font-medium text-foreground" : "text-muted-foreground hover:text-foreground",
            )}
          >
            {s}
          </button>
        ))}
      </nav>
      <div className="space-y-2">
        {section === "Appearance" && (
          <>
            <SettingRow id="set-dark" title="Dark theme" hint="Light theme when off">
              <Switch id="set-dark" checked={ui.theme === "dark"} onCheckedChange={ui.toggleTheme} />
            </SettingRow>
            <SettingRow id="set-compact" title="Compact agent cards" hint="Fit more agents in the sidebar">
              <Switch id="set-compact" />
            </SettingRow>
            <PickerField id="set-accent" label="Accent" options={["Red", "Amber", "Blue", "Green"]} inheritLabel="Default (Red)" />
          </>
        )}
        {section === "Daemon" && (
          <>
            <RunnerRow />
            <PickerField id="set-daemon" label="Daemon" options={["This computer", "vps-runner-01"]} inheritLabel="This computer" />
            <SettingRow id="set-auto" title="Start daemon with the app" hint="Runs in the background">
              <Switch id="set-auto" defaultChecked />
            </SettingRow>
          </>
        )}
        {section === "Notifications" && (
          <SettingRow id="set-toast" title="Milestone toasts" hint="One toast when a goal is met">
            <Switch id="set-toast" defaultChecked />
          </SettingRow>
        )}
        {section === "Voice" && (
          <SettingRow id="set-tts" title="Read comms aloud" hint="Uses the TTS plugin when installed">
            <Switch id="set-tts" />
          </SettingRow>
        )}
        {section === "About" && (
          <div className="rounded-lg border bg-card p-3 text-[13px]">
            <p className="font-semibold">Infantry AI</p>
            <p className="font-mono text-xs text-muted-foreground">build 1.6.0+20261117.2317.6afaed5e8 · Tauri v2 template</p>
          </div>
        )}
      </div>
    </div>
  );
}
