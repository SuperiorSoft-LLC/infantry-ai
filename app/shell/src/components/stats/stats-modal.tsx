// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Stats modal: the top-right Stats button opens it (almost full screen, the one AppDialog). Tabs, as the Kotlin Stats
// plugin groups them: Session (the selected session's tiles, context gauge, charts, what filled the context), Tokens
// (by day / week), Cost (harness comparison + estimated cost), Context (per session + compactions) and Pace.
import { useEffect, useMemo, useState } from "react";
import { BarChart3 } from "lucide-react";
import { useStore } from "zustand";
import {
  CHART_KINDS, COST_BYS, avgContextText, cacheHitText, compareHarnesses, contextSeries, costTable, createStatsMeterStore, deltaText,
  gaugeOver, gaugePercent, meterCostText, meterPeriods, NA, NONE, parseTokens, pctText, requestSessions, resolveAxis, shareText,
  meterSplit, statsCompact, statsCost, statsDuration, statsGrouped, totalRows, turnsText, usdText, watchLine,
  type ChartKind, type ChartScaleSetting, type CostBy, type CostNames, type MeterAgg, type MeterSplit, type SessionStats, type StatsMeterStore, type StatsPoint,
  type UsageMeterReport,
} from "@infantry/client";
import { AppDialog, Chip, EmptyState, PanelCard, Tabs, TabsContent, TabsList, TabsTrigger, cn } from "@infantry/ui";
import { daemonStore } from "@/daemon/daemon";
import { useDaemon } from "@/daemon/daemon";
import { useAgent } from "@/daemon/agents";
import { useUi } from "@/state/ui-state";
import { sessionActions } from "@/components/session/session-actions";
import { PaceBody } from "@/components/tabs/pace-tab";

/** How often the open modal asks the daemon for the selected session's numbers again. */
export const STATS_REFRESH_MS = 3_000;

let meter: StatsMeterStore | null = null;
const meterStore = (): StatsMeterStore => (meter ??= createStatsMeterStore(daemonStore().getState()));

export function StatsModal({ onClose }: { onClose: () => void }) {
  const ui = useUi();
  const agent = useAgent(ui.selectedAgentId);
  return (
    <AppDialog open onOpenChange={(o) => !o && onClose()} title="Stats" wide="screen" description={agent ? `${agent.name} · ${agent.id}` : "Pick a session in the sidebar for its Session tab"}>
      <Tabs defaultValue="session" className="h-full min-h-0">
        <TabsList>
          <TabsTrigger value="session">Session</TabsTrigger>
          <TabsTrigger value="tokens">Tokens by day/week</TabsTrigger>
          <TabsTrigger value="cost">Cost</TabsTrigger>
          <TabsTrigger value="context">Context</TabsTrigger>
          <TabsTrigger value="pace">Pace</TabsTrigger>
        </TabsList>
        <TabsContent value="session">{agent ? <SessionTab key={agent.id} sessionId={agent.id} /> : <EmptyState icon={<BarChart3 className="size-6" />} title="No session selected" hint="Pick a session in the sidebar to see its turns, tools, tokens and cost." />}</TabsContent>
        <TabsContent value="tokens"><MeterTab view="tokens" /></TabsContent>
        <TabsContent value="cost"><MeterTab view="cost" /></TabsContent>
        <TabsContent value="context"><MeterTab view="context" /></TabsContent>
        <TabsContent value="pace"><PaceBody /></TabsContent>
      </Tabs>
    </AppDialog>
  );
}

// ---------------------------------------------------------------- Session tab

function Tile({ label, value, hint, testId, extra }: { label: string; value: string; hint?: string; testId: string; extra?: React.ReactNode }) {
  return (
    <div className="rounded-lg border bg-card px-3 py-2.5">
      <div className="text-[10px] tracking-wide text-muted-foreground uppercase">{label}</div>
      <div className="mt-1 flex items-center gap-2">
        <span className="truncate text-[15px] font-semibold" data-testid={`stats-tile-${testId}`}>{value}</span>
        {extra}
      </div>
      {hint && <div className="text-[10px] text-muted-foreground">{hint}</div>}
    </div>
  );
}

const clock = (at?: number | null): string => (at == null ? NONE : new Date(at).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" }));
const titleCase = (s?: string | null): string => (s ? s[0]!.toUpperCase() + s.slice(1) : NONE);

function SessionTab({ sessionId }: { sessionId: string }) {
  const [s, setS] = useState<SessionStats | null>(null);
  const [why, setWhy] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    const load = (): void => {
      sessionActions().stats(sessionId).then((r) => live && (setS(r), setWhy(null)), (e) => live && setWhy(String(e)));
    };
    load();
    const id = setInterval(load, STATS_REFRESH_MS);
    return () => {
      live = false;
      clearInterval(id);
    };
  }, [sessionId]);
  if (why) return <p className="text-sm text-danger">{why}</p>;
  if (!s) return <p className="text-xs text-muted-foreground">Reading this session&apos;s record...</p>;
  const series = s.series ?? [];
  const noUsage = `no usage data for ${s.harness}`;
  const copy = (): void => void navigator.clipboard?.writeText(s.providerSessionId ?? "");
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2 text-[10px] tracking-wider text-muted-foreground uppercase">
        <span className="font-semibold">Session</span>
        <Chip tone="red" mono>{s.harness}</Chip>
        <span className="ml-auto normal-case">refreshes every {STATS_REFRESH_MS / 1000}s</span>
      </div>
      <div className="grid grid-cols-[repeat(auto-fill,minmax(220px,1fr))] gap-2">
        <Tile label="Duration" value={statsDuration(s.durationMs)} hint={s.startedAt != null ? `since ${clock(s.startedAt)}` : undefined} testId="duration" />
        <Tile label="Status" value={titleCase(s.status)} testId="status" />
        <Tile label="Exchanges" value={`${s.userPrompts ?? NONE} / ${s.assistantTurns ?? NONE}`} hint="prompts / turns" testId="exchanges" />
        <Tile label="Tool uses" value={statsCompact(s.toolUses)} testId="tools" />
        <Tile label="Models" value={s.models?.length ? s.models.join(", ") : NONE} testId="models" />
        <Tile
          label="Session id"
          value={s.providerSessionId?.trim() ? s.providerSessionId.slice(0, 8) : NONE}
          hint="the harness's own id"
          testId="session"
          extra={s.providerSessionId?.trim() ? <button type="button" onClick={copy} className="text-[10px] text-info hover:underline">copy</button> : undefined}
        />
        <Tile label="Billed tokens" value={series.length === 0 ? noUsage : statsCompact(s.billedTokens)} hint="all token classes, running total" testId="billed" />
        <Tile label="Cost" value={statsCost(s.costTotal)} hint="as reported by the harness" testId="cost" />
      </div>
      <Section title="Context window"><Gauge s={s} /></Section>
      <Section title="Over the session"><Charts s={s} series={series} noUsage={noUsage} /></Section>
      <Section title="What filled the context"><Sources s={s} /></Section>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="pb-1.5 text-[10px] font-semibold tracking-wider text-muted-foreground uppercase">{title}</div>
      {children}
    </div>
  );
}

function Gauge({ s }: { s: SessionStats }) {
  const pct = gaugePercent(s);
  const over = gaugeOver(s);
  return (
    <div className="rounded-lg border bg-card p-3" data-testid="stats-gauge">
      <div className="flex items-center font-mono text-xs">
        <span className="flex-1">{statsGrouped(s.lastContextTokens)} / {s.modelContextLimit ? statsGrouped(s.modelContextLimit) : "limit unknown"}</span>
        <span className={cn("font-semibold", over ? "text-danger" : "text-muted-foreground")} data-testid="stats-gauge-percent">{pct != null ? `${pct}%` : NONE}</span>
      </div>
      {pct != null && (
        <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-border">
          <div className={cn("h-full", over ? "bg-danger" : "bg-info")} style={{ width: `${Math.min(100, Math.max(0, pct))}%` }} />
        </div>
      )}
      <p className="mt-1.5 text-[10px] text-muted-foreground">
        {pct == null ? "Size of the last request. Limit unknown: no local catalog states this model's window." : "Size of the last request against the model's window."}
      </p>
    </div>
  );
}

function loadScale(sessionId: string, kind: ChartKind): ChartScaleSetting {
  try {
    const raw = localStorage.getItem(`chartScale.${kind}.${sessionId}`);
    if (raw === "limit") return { kind: "limit" };
    if (raw === "fit") return { kind: "fit" };
    if (raw?.startsWith("fixed:")) {
      const n = Number(raw.slice(6));
      if (n > 0) return { kind: "fixed", tokens: n };
    }
  } catch {
    /* storage unavailable: the default */
  }
  return { kind: "default" };
}
function saveScale(sessionId: string, kind: ChartKind, v: ChartScaleSetting): void {
  try {
    localStorage.setItem(`chartScale.${kind}.${sessionId}`, v.kind === "fixed" ? `fixed:${v.tokens}` : v.kind);
  } catch {
    /* storage unavailable */
  }
}

function Charts({ s, series, noUsage }: { s: SessionStats; series: StatsPoint[]; noUsage: string }) {
  const timeAvailable = series.length > 0 && series.every((p) => p.at != null);
  const [byTime, setByTime] = useState(true);
  const useTime = byTime && timeAvailable;
  const pct = gaugePercent(s);
  return (
    <div className="rounded-lg border bg-card p-3">
      <div className="flex items-center gap-1 text-[10px] text-muted-foreground">
        <span className="flex-1">{series.length} requests</span>
        <Pill on={useTime} disabled={!timeAvailable} onClick={() => setByTime(true)} testId="stats-axis-time">Time</Pill>
        <Pill on={!useTime} onClick={() => setByTime(false)} testId="stats-axis-requests">Requests</Pill>
      </div>
      {series.length === 0 ? (
        <p className="mt-2 text-[11px] text-muted-foreground">{noUsage}</p>
      ) : (
        <>
          <div className="mt-2 flex items-center gap-2 text-[11px]">
            <span>Context tokens</span>
            <span className="text-[10px] text-muted-foreground" data-testid="stats-context-limit-percent">{pct != null ? `${pct}% of limit` : "limit unknown"}</span>
          </div>
          <ScaleChart sessionId={s.sessionId} kind="context" series={series} byTime={useTime} value={(p) => p.contextTokens} limit={s.modelContextLimit} label="Context tokens per request" />
          <div className="mt-3 text-[11px]">Billed tokens, running total</div>
          <ScaleChart sessionId={s.sessionId} kind="billed" series={series} byTime={useTime} value={(p) => p.cumulativeBilledTokens} limit={null} label="Billed tokens running total" />
          <div className="mt-1 flex justify-between text-[10px] text-muted-foreground">
            <span>{useTime ? clock(series[0]!.at) : "#1"}</span>
            <span>{useTime ? clock(series[series.length - 1]!.at) : `#${series.length}`}</span>
          </div>
        </>
      )}
    </div>
  );
}

function Pill({ on, onClick, disabled, children, testId }: { on: boolean; onClick: () => void; disabled?: boolean; children: React.ReactNode; testId?: string }) {
  return (
    <button
      type="button"
      data-testid={testId}
      disabled={disabled}
      onClick={onClick}
      className={cn("rounded px-2 py-0.5 text-[10px] whitespace-nowrap", on ? "bg-border font-semibold text-foreground" : "text-muted-foreground hover:bg-accent", disabled && "opacity-40")}
    >
      {children}
    </button>
  );
}

function ScaleChart({ sessionId, kind, series, byTime, value, limit, label }: {
  sessionId: string; kind: ChartKind; series: StatsPoint[]; byTime: boolean; value: (p: StatsPoint) => number; limit?: number | null; label: string;
}) {
  const [setting, setSetting] = useState<ChartScaleSetting>(() => loadScale(sessionId, kind));
  const [custom, setCustom] = useState<string | null>(null);
  const apply = (v: ChartScaleSetting): void => { setSetting(v); saveScale(sessionId, kind, v); };
  const axis = resolveAxis(kind, setting, Math.max(...series.map(value)), limit);
  const eff: ChartScaleSetting = setting.kind === "default" ? { kind: "fixed", tokens: CHART_KINDS[kind].defaultMax } : setting;
  const parsed = custom == null ? null : parseTokens(custom);
  return (
    <div>
      <div className="flex flex-wrap items-center gap-1 py-1">
        <span className="text-[10px] text-muted-foreground">Scale</span>
        {CHART_KINDS[kind].presets.map((p) => (
          <Pill key={p} on={eff.kind === "fixed" && eff.tokens === p} onClick={() => apply({ kind: "fixed", tokens: p })} testId={`stats-scale-${kind}-${p}`}>{statsCompact(p)}</Pill>
        ))}
        {kind === "context" && <Pill on={eff.kind === "limit"} disabled={!limit} onClick={() => apply({ kind: "limit" })}>Model limit</Pill>}
        <Pill on={eff.kind === "fit"} onClick={() => apply({ kind: "fit" })}>Fit</Pill>
        <Pill on={custom != null} onClick={() => setCustom(custom == null ? "" : null)}>Custom</Pill>
        <Pill on={false} disabled={setting.kind === "default"} onClick={() => { apply({ kind: "default" }); setCustom(null); }}>Reset to default</Pill>
        {custom != null && (
          <span className="flex items-center gap-1">
            <input value={custom} onChange={(e) => setCustom(e.target.value)} placeholder="e.g. 300k" className="h-6 w-24 rounded border bg-transparent px-1.5 font-mono text-[11px]" />
            <Pill on={false} disabled={parsed == null} onClick={() => { if (parsed) { apply({ kind: "fixed", tokens: parsed }); setCustom(null); } }}>Set</Pill>
          </span>
        )}
      </div>
      {axis.overScale && <div className="text-[10px] text-danger" data-testid={`stats-over-scale-${kind}`}>over scale: line clipped at {statsCompact(axis.max)}</div>}
      <LineChart series={series} byTime={byTime} value={value} max={axis.max} limit={limit && limit > 0 && limit <= axis.max ? limit : null} label={label} testId={`stats-chart-${kind}`} />
    </div>
  );
}

function LineChart({ series, byTime, value, max, limit, label, testId }: {
  series: StatsPoint[]; byTime: boolean; value: (p: StatsPoint) => number; max: number; limit: number | null; label: string; testId: string;
}) {
  const W = 1000, H = 130;
  const t0 = series[0]!.at ?? 0;
  const span = Math.max(1, (series[series.length - 1]!.at ?? 0) - t0);
  const x = (i: number, p: StatsPoint): number => (byTime ? (((p.at ?? 0) - t0) / span) * W : series.length === 1 ? W / 2 : (i / (series.length - 1)) * W);
  const y = (v: number): number => H - (v / max) * H;
  const pts = series.map((p, i) => `${x(i, p).toFixed(1)},${y(value(p)).toFixed(1)}`);
  const area = `${x(0, series[0]!).toFixed(1)},${H} ${pts.join(" ")} ${x(series.length - 1, series[series.length - 1]!).toFixed(1)},${H}`;
  return (
    <div className="flex gap-2">
      <div className="flex w-11 shrink-0 flex-col justify-between text-[9px] text-muted-foreground" style={{ height: H }}>
        <span>{statsCompact(max)}</span>
        <span>0</span>
      </div>
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" className="w-full overflow-hidden" style={{ height: H }} role="img" aria-label={label} data-testid={testId}>
        <line x1={0} y1={H / 2} x2={W} y2={H / 2} className="stroke-border" strokeWidth={1} vectorEffect="non-scaling-stroke" />
        <line x1={0} y1={H} x2={W} y2={H} className="stroke-border" strokeWidth={1} vectorEffect="non-scaling-stroke" />
        <polygon points={area} className="fill-info/15" />
        <polyline points={pts.join(" ")} fill="none" className="stroke-info" strokeWidth={1.5} vectorEffect="non-scaling-stroke" />
        {limit != null && <line x1={0} y1={y(limit)} x2={W} y2={y(limit)} className="stroke-muted-foreground" strokeWidth={1} strokeDasharray="6 4" vectorEffect="non-scaling-stroke" />}
      </svg>
    </div>
  );
}

function Sources({ s }: { s: SessionStats }) {
  const rows = s.topSources ?? [];
  const max = Math.max(1, ...rows.map((r) => r.tokensAdded ?? 0));
  return (
    <div className="rounded-lg border bg-card p-3">
      <div className="flex text-[10px] text-muted-foreground">
        <span className="flex-1">SOURCE</span>
        <span className="w-14">CALLS</span>
        <span className="w-36">TOKENS ADDED</span>
      </div>
      {rows.length === 0 && <p className="mt-1.5 text-xs text-muted-foreground">{NONE}</p>}
      {rows.map((r) => (
        <div key={r.tool} className="mt-2 flex items-center" data-testid={`stats-source-${r.tool}`}>
          <span className="flex-1 truncate text-xs">{r.tool}</span>
          <span className="w-14 font-mono text-xs">{r.calls}</span>
          <div className="w-36">
            <div className="font-mono text-xs">{statsCompact(r.tokensAdded)}</div>
            {r.tokensAdded != null && (
              <div className="mt-0.5 h-1 w-28 overflow-hidden rounded bg-border"><div className="h-full bg-info" style={{ width: `${Math.min(100, (r.tokensAdded / max) * 100)}%` }} /></div>
            )}
          </div>
        </div>
      ))}
      {s.unattributedToolTokens != null && s.unattributedToolTokens > 0 && (
        <p className="mt-2.5 text-[10px] text-muted-foreground">{statsCompact(s.unattributedToolTokens)} tokens followed steps that ran several tools at once and are not split between them.</p>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- the daemon-wide meter tabs

function MeterTab({ view }: { view: "tokens" | "cost" | "context" }) {
  const store = meterStore();
  const report = useStore(store, (s) => s.report);
  const refusal = useStore(store, (s) => s.refusal);
  const live = useDaemon((s) => s.connection.state === "open");
  useEffect(() => {
    if (live) store.getState().refresh();
  }, [live, store]);
  if (refusal && !report) return <p className="text-sm text-danger">{refusal}</p>;
  if (!report) return <p className="text-xs text-muted-foreground">Reading the usage meter...</p>;
  return view === "tokens" ? <TokensView report={report} /> : view === "cost" ? <CostView report={report} /> : <ContextView report={report} />;
}

const SPLITS: { id: MeterSplit; label: string }[] = [{ id: "source", label: "Source" }, { id: "role", label: "Role" }, { id: "model", label: "Model" }];

function Segmented<T extends string | boolean>({ label, options, value, onChange }: { label: string; options: { id: T; label: string }[]; value: T; onChange: (v: T) => void }) {
  return (
    <div className="flex items-center gap-2">
      <span className="text-[10px] tracking-wide text-muted-foreground uppercase">{label}</span>
      <div className="flex rounded-lg bg-muted p-0.5">
        {options.map((o) => (
          <Pill key={String(o.id)} on={o.id === value} onClick={() => onChange(o.id)}>{o.label}</Pill>
        ))}
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <span className="rounded border px-2 py-0.5 text-[11px]">
      <span className="text-muted-foreground">{label} </span>
      <span className="font-mono">{value}</span>
    </span>
  );
}

function TokensView({ report }: { report: UsageMeterReport }) {
  const [weekly, setWeekly] = useState(false);
  const [split, setSplit] = useState<MeterSplit>("source");
  const periods = useMemo(() => meterPeriods(report.rows, weekly), [report, weekly]);
  const turnsMax = Math.max(1, ...periods.slice(0, 14).map(([, rs]) => totalRows(rs).turns));
  return (
    <div className="space-y-4" data-testid="stats-usage-meter">
      <div className="flex flex-wrap gap-6" data-testid="stats-tokens-toggle">
        <Segmented label="Period" value={weekly} options={[{ id: false, label: "By day" }, { id: true, label: "By week" }]} onChange={setWeekly} />
        <Segmented label="Group by" value={split} options={SPLITS} onChange={setSplit} />
      </div>
      <PanelCard title={`TOKENS ${weekly ? "BY WEEK" : "BY DAY"}`} className="" >
        {periods.length === 0 && <p className="text-xs text-muted-foreground">No runs recorded yet.</p>}
        {periods.length > 0 && (
          <div className="mb-3 rounded-md border p-2.5">
            <div className="text-[10px] font-semibold tracking-wider text-muted-foreground uppercase">Turns (model requests) per {weekly ? "week" : "day"}</div>
            {periods.slice(0, 14).reverse().map(([key, rs]) => {
              const t = totalRows(rs).turns;
              return (
                <div key={key} className="mt-0.5 flex items-center gap-2">
                  <span className="w-20 text-[10px] text-muted-foreground">{key}</span>
                  <div className="flex-1"><div className="h-2 rounded-sm bg-info" style={{ width: `${(t / turnsMax) * 100}%` }} /></div>
                  <span className="w-14 text-right font-mono text-[10px]">{t}</span>
                </div>
              );
            })}
          </div>
        )}
        {periods.map(([key, rs], i) => {
          const t = totalRows(rs);
          const prev = periods[i + 1] ? totalRows(periods[i + 1]![1]) : null;
          return (
            <div key={key} className="mb-2 rounded-md border bg-muted/30 p-2.5">
              <div className="flex items-center text-xs">
                <span className="flex-1 font-semibold">{(weekly ? "week of " : "") + key}</span>
                <span className="font-semibold">{statsCompact(t.tokens)} tokens</span>
                <span className="ml-1.5 text-[10px] text-muted-foreground">{prevText(deltaText(t.tokens, prev?.tokens ?? null))}</span>
              </div>
              <div className="flex flex-wrap gap-1.5 py-1.5">
                <Stat label="Runs" value={`${t.runs}`} />
                <Stat label="Turns" value={`${t.turns} (${prevText(deltaText(t.turns, prev?.turns ?? null))})`} />
                <Stat label="In / out" value={`${statsCompact(t.input)} / ${statsCompact(t.output)}`} />
                <Stat label="Cache r / w" value={`${statsCompact(t.cacheRead)} / ${statsCompact(t.cacheWrite)}`} />
                <Stat label="Cache hit" value={cacheHitText(t)} />
                <Stat label="Avg ctx/turn" value={avgContextText(t)} />
                <Stat label="Cost" value={meterCostText(t)} />
              </div>
              {meterSplit(rs, split).map(([name, a]) => <SplitLine key={name} name={name} a={a} />)}
            </div>
          );
        })}
      </PanelCard>
      <p className="text-[10px] text-muted-foreground">Provider-reported only. Per-run rows kept this and last week, then daily rollups for {report.keepWeeks} weeks.</p>
      <p className="text-[10px] text-muted-foreground" data-testid="stats-turn-definition">A turn is one model request and its response (one provider usage report). A run is one agent process from prompt to exit; it holds many turns.</p>
      {report.watch ? <p className="text-[10px] text-muted-foreground" data-testid="stats-watch-meter">{watchLine(report.watch)}</p> : null}
    </div>
  );
}
const prevText = (d: string): string => (d === NONE ? "no previous" : `${d} vs previous`);

function SplitLine({ name, a }: { name: string; a: MeterAgg }) {
  return (
    <div className="mt-0.5 flex items-center text-[11px]">
      <span className="flex-1 truncate">{name}</span>
      <span className="w-20 text-right font-mono text-muted-foreground">{a.turns} turns</span>
      <span className="w-16 text-right font-mono">{statsCompact(a.tokens)}</span>
      <span className="w-24 text-right font-mono text-muted-foreground">hit {cacheHitText(a)}</span>
      <span className="w-24 text-right font-mono text-muted-foreground">ctx {avgContextText(a)}</span>
      <span className="w-16 text-right font-mono">{meterCostText(a)}</span>
    </div>
  );
}

function CostView({ report }: { report: UsageMeterReport }) {
  const [by, setBy] = useState<CostBy>("session");
  const [picked, setPicked] = useState<string | null>(null);
  const tickets = useDaemon((s) => s.tickets);
  const agents = useDaemon((s) => s.agents);
  const names = useMemo<CostNames>(
    () => ({
      tickets: Object.fromEntries(Object.values(tickets).map((t) => [t.id, t.title])),
      sessions: Object.fromEntries(Object.entries(agents).map(([id, a]) => [id, (a as { role?: string; provider?: string }).role ?? (a as { provider?: string }).provider ?? ""]).filter((e) => e[1])),
    }),
    [tickets, agents],
  );
  const lines = useMemo(() => compareHarnesses(report), [report]);
  const table = useMemo(() => costTable(report, by, names), [report, by, names]);
  const max = Math.max(1e-9, table.rows[0]?.cost ?? 0);
  return (
    <div className="space-y-4">
      <PanelCard title="HARNESS COMPARISON (turns, tokens per turn, cache hit, estimated cost)" bodyClassName="space-y-1" >
        {lines.length === 0 && <p className="text-xs text-muted-foreground">No data yet.</p>}
        {lines.map((l) => (
          <div key={l.harness} className="flex text-[11px]" data-testid="stats-compare">
            <span className="flex-1">{l.harness}</span>
            <span className="font-mono text-muted-foreground">
              {l.turns} turns  {l.tokensPerTurn != null ? statsCompact(l.tokensPerTurn) : NA}/turn  ctx {l.avgContext != null ? statsCompact(l.avgContext) : NA}  hit {pctText(l.cacheHit)}  {usdText(l.estCost)}
            </span>
          </div>
        ))}
        {lines.length > 0 && <p className="pt-1 text-[10px] text-muted-foreground">Cost is an estimate from the price table in the daemon&apos;s usage-prices.json, not a bill.</p>}
      </PanelCard>
      <PanelCard title="ESTIMATED COST BY" bodyClassName="space-y-1.5">
        <div className="flex gap-1">
          {COST_BYS.map((c) => <Pill key={c} on={by === c} onClick={() => { setBy(c); setPicked(null); }}>{c[0]!.toUpperCase() + c.slice(1)}</Pill>)}
        </div>
        <p className="text-[10px] text-muted-foreground" data-testid="stats-cost-note">
          Estimate, not a bill: tokens recorded per model request x the price table in the daemon&apos;s usage-prices.json. Biggest first; share is of the priced total.
        </p>
        {table.rows.length === 0 && <p className="text-xs text-muted-foreground">No data yet.</p>}
        {table.rows.slice(0, 12).map((r) => (
          <button key={r.key} type="button" className="block w-full rounded text-left" data-testid="stats-cost-row" onClick={() => setPicked(picked === r.key ? null : r.key)}>
            <div className="flex items-center gap-2">
              <span className="min-w-0 flex-1 truncate text-[11px]">{r.label}{r.detail && <span className="ml-1.5 text-[10px] text-muted-foreground">{r.detail}</span>}</span>
              <span className="min-w-14 text-right text-[11px] font-semibold">{usdText(r.cost)}</span>
              <span className="w-10 text-right text-[10px] text-muted-foreground">{shareText(r.share)}</span>
            </div>
            <div className="mt-0.5 h-1.5 overflow-hidden rounded-full bg-border">
              {r.cost != null && <div className="h-full bg-info" style={{ width: `${Math.max(2, Math.min(100, (r.cost / max) * 100))}%` }} />}
            </div>
            {picked === r.key && (
              <div className="text-[10px] text-muted-foreground">{turnsText(r.turns)} | {r.cost != null ? `${usdText(r.cost / Math.max(1, r.turns))} per turn` : "no price for this model"}</div>
            )}
          </button>
        ))}
        {table.rows.length > 12 && <p className="text-[10px] text-muted-foreground">+{table.rows.length - 12} more not shown</p>}
        {table.rows.length > 0 && (
          <div className="mt-2 flex items-center border-t pt-1.5 text-[11px]" data-testid="stats-cost-total">
            <span className="font-semibold">Total</span>
            <span className="ml-1.5 text-[10px] text-muted-foreground">{turnsText(table.turns)}</span>
            <span className="flex-1" />
            <span className="font-semibold">{usdText(table.total)}</span>
          </div>
        )}
        {table.unpriced > 0 && <p className="text-[10px] text-muted-foreground">{table.unpriced} without a price are not in the total</p>}
      </PanelCard>
    </div>
  );
}

function ContextView({ report }: { report: UsageMeterReport }) {
  const sessions = useMemo(() => requestSessions(report.requests).slice(0, 6), [report]);
  return (
    <PanelCard title="CONTEXT PER SESSION (tokens re-read each turn, oldest first)" bodyClassName="space-y-1">
      {sessions.length === 0 && <p className="text-xs text-muted-foreground">No data yet.</p>}
      {sessions.map((id) => {
        const series = contextSeries(report.requests, id);
        const max = Math.max(1, ...series.map((v) => v ?? 0));
        const last = series[series.length - 1];
        return (
          <div key={id} className="flex items-center gap-1.5">
            <span className="w-16 text-[10px] text-muted-foreground">{id.slice(0, 8)}</span>
            <div className="flex h-4 flex-1 items-end gap-px">
              {series.slice(-60).map((v, i) => (
                <div key={i} className={cn("flex-1", v == null ? "bg-border" : "bg-info")} style={{ height: `${v == null ? 5 : Math.max(5, (v / max) * 100)}%` }} />
              ))}
            </div>
            <span className="w-12 pl-1.5 text-[10px]">{last != null ? statsCompact(last) : NA}</span>
          </div>
        );
      })}
      {report.events.slice(-5).reverse().map((e) => (
        <p key={`${e.ts}-${e.sessionId}`} className="text-[10px] text-muted-foreground">
          {e.kind} in {e.sessionId.slice(0, 8)} ({e.harness}{e.trigger ? `, ${e.trigger}` : ""}), context before {e.tokensBefore != null ? statsCompact(e.tokensBefore) : NA}
        </p>
      ))}
    </PanelCard>
  );
}
