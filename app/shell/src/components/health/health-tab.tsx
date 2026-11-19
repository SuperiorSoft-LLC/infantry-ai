// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Health view (RUST-UI-5): the daemon's `health` report as cards: the verdict, the daemon line, resources, plugins
// (faults first), MCP bridges, environment and the last restart events. Re-read on open and on Refresh.
import { useEffect } from "react";
import { Activity, RefreshCw } from "lucide-react";
import { age, healthSummary, type HealthReport } from "@infantry/client";
import { Button, Chip, EmptyState, PanelCard } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { TabPage } from "@/components/tabs/tab-page";
import { RefusalBanner } from "@/components/knowledge/parts";
import { healthStore, useHealth } from "@/plugins/use-plugins";

function Row({ k, v }: { k: string; v: unknown }) {
  if (v === undefined || v === null || v === "") return null;
  const text = typeof v === "string" || typeof v === "number" ? String(v) : Array.isArray(v) ? v.map((x) => (typeof x === "object" ? JSON.stringify(x) : String(x))).join(", ") : JSON.stringify(v);
  return (
    <div className="flex gap-3 py-0.5 text-[13px]">
      <dt className="w-32 shrink-0 text-muted-foreground">{k}</dt>
      <dd className="min-w-0 flex-1 truncate font-mono text-xs" title={text}>
        {text}
      </dd>
    </div>
  );
}

function Empty({ text }: { text: string }) {
  return <p className="text-xs text-muted-foreground">{text}</p>;
}

function Body({ r }: { r: HealthReport }) {
  const sum = healthSummary(r);
  const d = r.daemon;
  const env = r.environment ?? {};
  const plugins = [...r.plugins].sort((a, b) => Number(a.status === "loaded") - Number(b.status === "loaded") || a.id.localeCompare(b.id));
  return (
    <div className="grid gap-3 lg:grid-cols-2">
      <PanelCard title="Daemon" action={<Chip tone={sum.tone}>{sum.headline}</Chip>}>
        <dl>
          <Row k="pid / port" v={`${d.pid ?? "?"} / ${d.port ?? "?"}`} />
          <Row k="build" v={d.build} />
          <Row k="uptime" v={d.uptimeMs != null ? age(d.uptimeMs) : null} />
          <Row k="last restart" v={d.lastRestartCause ? `${d.lastRestartCause}${d.lastRestartAt ? ` (${age(r.at - d.lastRestartAt)} ago)` : ""}` : null} />
          <Row k="source" v={d.source} />
        </dl>
      </PanelCard>
      <PanelCard title="Resources">
        <dl>
          <Row k="free memory" v={r.freeMemoryMb != null ? `${r.freeMemoryMb} MB` : "?"} />
          <Row k="free disk" v={r.freeDiskMb != null ? `${r.freeDiskMb} MB` : "?"} />
          <Row k="state dir" v={env.stateDir} />
        </dl>
      </PanelCard>
      <PanelCard title={`Plugins (${r.plugins.length})`}>
        {plugins.length === 0 ? (
          <Empty text="None reported." />
        ) : (
          <ul className="flex flex-wrap gap-1.5">
            {plugins.map((p) => (
              <li key={p.id}>
                <Chip tone={p.status === "loaded" ? "green" : "red"} mono>
                  {p.id}
                  {p.status !== "loaded" ? `: ${p.status}` : ""}
                </Chip>
              </li>
            ))}
          </ul>
        )}
      </PanelCard>
      <PanelCard title={`MCP bridges (${r.mcp.length})`}>
        {r.mcp.length === 0 ? (
          <Empty text="None." />
        ) : (
          <ul className="space-y-1 font-mono text-xs">
            {r.mcp.map((m, i) => (
              <li key={i} className="truncate">
                {Object.entries(m)
                  .map(([k, v]) => `${k}=${typeof v === "object" ? JSON.stringify(v) : String(v)}`)
                  .join("  ")}
              </li>
            ))}
          </ul>
        )}
      </PanelCard>
      <PanelCard title="Environment">
        <dl>
          <Row k="os / arch" v={[env.os, env.arch].filter(Boolean).join(" / ")} />
          <Row k="shells" v={env.shells} />
          <Row k="project" v={env.projectPath} />
          <Row k="branch" v={env.gitBranch} />
          <Row k="harnesses" v={env.harnesses} />
          <Row k="owner ports" v={env.ownerPorts} />
          <Row k="test ports" v={env.testPortRange} />
          <Row k="cli" v={env.cliPath} />
        </dl>
      </PanelCard>
      <PanelCard title="Recent events">
        {r.events.length === 0 ? (
          <Empty text="No start or stop recorded." />
        ) : (
          <ul className="space-y-1 text-xs">
            {[...r.events].reverse().map((e, i) => (
              <li key={i} className="flex gap-2">
                <span className="w-16 shrink-0 text-muted-foreground">{age(r.at - e.at)} ago</span>
                <span className="font-semibold">{e.kind}</span>
                {e.cause && <span className="truncate text-muted-foreground">{e.cause}</span>}
              </li>
            ))}
          </ul>
        )}
      </PanelCard>
    </div>
  );
}

export function HealthTab() {
  const report = useHealth((s) => s.report);
  const loading = useHealth((s) => s.loading);
  const refusal = useHealth((s) => s.refusal);
  const link = useDaemon((s) => s.connection.state);
  useEffect(() => {
    if (link === "open") healthStore().getState().refresh();
  }, [link]);
  return (
    <TabPage
      title="Health"
      description="The daemon, its plugins and this machine"
      actions={
        <Button variant="ghost" size="sm" aria-label="Refresh" disabled={loading} onClick={() => healthStore().getState().refresh()}>
          <RefreshCw className={loading ? "animate-spin" : ""} />
        </Button>
      }
    >
      <RefusalBanner text={refusal} onDismiss={() => healthStore().setState({ refusal: null })} />
      {report ? <Body r={report} /> : <EmptyState icon={<Activity className="size-6" />} title={link !== "open" ? "Waiting for the daemon…" : loading ? "Reading health…" : "No report yet"} />}
    </TabPage>
  );
}
