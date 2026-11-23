// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// MCP, Review, Account, Sites and Services (RUST-UI-7), drawn from the daemon's frames like the Kotlin panels. Each panel
// asks its frame on open; a daemon with no unit for it answers a refusal, which the panel shows as "not served" (Served).
//   MCP      `mcp_list` -> servers (state, tools, enabled), grants; `mcp_set_enabled` / `mcp_test` / `mcp_revoke`
//   Review   the converged pods' nodes waiting for review (`pod_query` / `pod` pushes)
//   Account  `account_status` (Infantry_Account's `account.status`); Services lists its account providers
//   Sites    `sites_list` (Infantry_Sites' `infantry_sites_list`): hosting servers, read-only
import { useEffect, useMemo, useRef } from "react";
import { Globe, GitPullRequest, Plug, Server, UserCircle } from "lucide-react";
import { accountProviders, flowView, reviewQueue, signInBody, signInTracker, signInUri, signOutBody, type AccountProvider, type McpServerView } from "@infantry/client";
import { Button, Chip, EmptyState, PanelCard, Switch } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { TabPage } from "@/components/tabs/tab-page";
import { ago, RefusalBanner } from "@/components/knowledge/parts";
import { RefreshButton, Served } from "./parts";
import { host } from "@/host";
import { accountStore, mcpStore, podsStore, probeStore, useQuery, useQueryOnOpen } from "./use-panels";

type Tone = "neutral" | "red" | "green" | "blue" | "amber";
const stateTone = (s: string): Tone => (/ok|ready|connected|running/i.test(s) ? "green" : /fail|error|denied/i.test(s) ? "red" : /auth|pending|start/i.test(s) ? "amber" : "neutral");

function McpRow({ s }: { s: McpServerView }) {
  const store = mcpStore();
  const owned = s.source === "infantry";
  return (
    <li className="space-y-1 py-2">
      <div className="flex items-center gap-2 text-[13px]">
        <span className="min-w-0 flex-1 truncate font-medium" title={s.detail ?? undefined}>
          {s.name}
        </span>
        <Chip tone={stateTone(s.state)}>{s.state}</Chip>
        {s.toolCount != null && <Chip mono>{`${s.toolCount} tools`}</Chip>}
        {owned && <Switch aria-label={`${s.enabled ? "Disable" : "Enable"} ${s.name}`} checked={s.enabled} onCheckedChange={(on) => store.getState().send("mcp_set_enabled", JSON.stringify({ name: s.name, enabled: on, scope: s.scope, projectId: s.projectId }))} />}
      </div>
      <div className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        <Chip mono>{s.transport}</Chip>
        <span>{s.source}</span>
        {s.scope && <span>· {s.scope}</span>}
        {s.shadowedBy && <Chip tone="amber">{`shadowed by ${s.shadowedBy}`}</Chip>}
        <Button variant="ghost" size="xs" className="ml-auto" onClick={() => store.getState().send("mcp_test", JSON.stringify({ name: s.name, target: s.target, projectId: s.projectId }))}>
          Test
        </Button>
      </div>
    </li>
  );
}

export function McpPanel() {
  const store = mcpStore();
  useQueryOnOpen(store);
  const data = useQuery(store, (s) => s.data);
  const loaded = useQuery(store, (s) => s.loaded);
  const byTarget = useMemo(() => {
    const m = new Map<string, McpServerView[]>();
    for (const s of data.servers) m.set(s.target, [...(m.get(s.target) ?? []), s]);
    return [...m.entries()];
  }, [data.servers]);
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <span className="text-xs text-muted-foreground">MCP servers by target, and who may use them</span>
        <RefreshButton store={store} />
      </div>
      <Served store={store} frames="mcp_list, mcp_set_enabled, mcp_test, mcp_assign, mcp_revoke, mcp_plan">
        {byTarget.length === 0 ? (
          <EmptyState icon={<Plug className="size-6" />} title={loaded ? "No MCP servers" : "Reading MCP servers…"} />
        ) : (
          byTarget.map(([target, list]) => (
            <PanelCard key={target} title={target} action={<Chip mono>{list.length}</Chip>}>
              <ul className="divide-y">
                {list.map((s) => (
                  <McpRow key={`${s.source}/${s.name}`} s={s} />
                ))}
              </ul>
            </PanelCard>
          ))
        )}
        {data.grants.length > 0 && (
          <PanelCard title={`Grants (${data.grants.length})`}>
            <ul className="space-y-1.5 text-xs">
              {data.grants.map((g) => (
                <li key={g.id} className="flex items-center gap-2">
                  <span className="min-w-0 flex-1 truncate" title={g.purpose}>
                    <span className="font-medium">{g.server}</span> → {g.scope} {g.target}
                  </span>
                  <span className="text-muted-foreground">{ago(g.approvedAt)}</span>
                  <Button variant="ghost" size="xs" onClick={() => store.getState().send("mcp_revoke", JSON.stringify({ grantId: g.id }))}>
                    Revoke
                  </Button>
                </li>
              ))}
            </ul>
          </PanelCard>
        )}
      </Served>
    </div>
  );
}

export function ReviewPanel() {
  const store = podsStore();
  useQueryOnOpen(store, "");
  const pods = useQuery(store, (s) => s.data);
  const sessions = useDaemon((s) => s.sessions);
  const queue = useMemo(() => reviewQueue(pods), [pods]);
  return (
    <Served store={store} frames="pod_query, a pod list, a review verdict">
      {queue.length === 0 ? (
        <EmptyState icon={<GitPullRequest className="size-6" />} title="Nothing is waiting for review" hint="Converged pods queue their nodes here; the client sees only pods it submitted or queried (there is no pod-list frame)." />
      ) : (
        <div className="space-y-2">
          {queue.map((e) => (
            <PanelCard key={`${e.podId}/${e.nodeName}`} title={e.nodeName}>
              <p className="text-xs text-muted-foreground">{e.podGoal}</p>
              {e.branch && <p className="font-mono text-xs text-info">{e.branch}</p>}
              {e.workingDir && <p className="truncate font-mono text-[11px] text-muted-foreground">{e.workingDir}</p>}
              {e.sessionId && !sessions[e.sessionId] && <p className="text-[11px] text-muted-foreground">Its agent is gone.</p>}
            </PanelCard>
          ))}
        </div>
      )}
    </Served>
  );
}

const str = (o: Record<string, unknown> | null, k: string): string | null => (o && typeof o[k] === "string" ? (o[k] as string) : null);
const list = (o: Record<string, unknown> | null, k: string): Record<string, unknown>[] => (o && Array.isArray(o[k]) ? (o[k] as Record<string, unknown>[]) : []);

/** Account state: signed in or not, the plan, and why when not. */
export function AccountPanel({ quiet = false }: { quiet?: boolean }) {
  const store = accountStore();
  useQueryOnOpen(store);
  const data = useQuery(store, (s) => s.data);
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <span className="text-xs text-muted-foreground">Your SuperiorSoft account</span>
        <RefreshButton store={store} />
      </div>
      <Served store={store} banner={!quiet} frames="account_status (account.status), account_sign_in, account_sign_out">
        {!data ? (
          <EmptyState icon={<UserCircle className="size-6" />} title="Reading the account…" />
        ) : (
          <PanelCard title={str(data, "email") ?? "Account"} action={<Chip tone={str(data, "state") === "signed_in" ? "green" : "amber"}>{str(data, "state") ?? "unknown"}</Chip>}>
            <dl className="space-y-0.5 text-[13px]">
              {["plan", "planStatus", "message"].map((k) =>
                str(data, k) ? (
                  <div key={k} className="flex gap-3">
                    <dt className="w-24 text-muted-foreground">{k}</dt>
                    <dd className="min-w-0 flex-1 truncate">{str(data, k)}</dd>
                  </div>
                ) : null,
              )}
            </dl>
          </PanelCard>
        )}
      </Served>
    </div>
  );
}

/** Hosting servers (read-only here). */
export function SitesPanel() {
  const store = probeStore("sites_list");
  useQueryOnOpen(store);
  const data = useQuery(store, (s) => s.data);
  const servers = list(data, "servers");
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between">
        <span className="text-xs text-muted-foreground">Your hosting servers</span>
        <RefreshButton store={store} />
      </div>
      <Served store={store} frames="sites_list (Infantry_Sites state)">
        {!data ? (
          <EmptyState icon={<Globe className="size-6" />} title="Reading sites…" />
        ) : servers.length === 0 ? (
          <EmptyState icon={<Globe className="size-6" />} title={str(data, "message") ?? "No servers"} />
        ) : (
          <ul className="space-y-2">
            {servers.map((s, i) => (
              <li key={str(s, "id") ?? i} className="rounded-lg border p-2 text-[13px]">
                <div className="flex items-center gap-2">
                  <span className="min-w-0 flex-1 truncate font-medium">{str(s, "name") ?? str(s, "id")}</span>
                  <Chip tone={stateTone(str(s, "status") ?? "")}>{str(s, "status") ?? "?"}</Chip>
                </div>
                <p className="font-mono text-xs text-muted-foreground">{[str(s, "ip"), str(s, "region"), str(s, "size")].filter(Boolean).join(" · ")}</p>
              </li>
            ))}
          </ul>
        )}
      </Served>
    </div>
  );
}

/** Sign in starts the device-code flow and opens its page; Sign out ends it. A refusal is shown by the store's `Served`. */
function SignInOut({ p }: { p: AccountProvider }) {
  const store = accountStore();
  const answers = probeStore("account_sign_in");
  const flow = flowView(p);
  const answer = useQuery(answers, (s) => s.data);
  const rawRefusal = useQuery(answers, (s) => s.refusal);
  // The answer store is shared by every card: only the card that clicked Sign in reacts to its answer or refusal.
  const code = signInTracker.owns(p.id, answer) ? answer : null;
  const signInRefusal = signInTracker.owns(p.id) ? rawRefusal : null;
  const refusal = useQuery(store, (s) => s.refusal);
  const shown = refusal ?? signInRefusal;
  const opened = useRef<string | null>(null);
  const start = () => {
    opened.current = null;
    signInTracker.start(p.id);
    answers.getState().clearRefusal();
    answers.getState().refresh(signInBody(p.id));
  };
  // The answer to our sign-in names the page to approve on: open it once per Sign in click (the store re-reads the account itself).
  useEffect(() => {
    const uri = signInUri(code);
    if (uri && opened.current !== uri) {
      opened.current = uri;
      void host.openExternal(uri).catch(() => undefined);
    }
  }, [code]);
  // A pending flow finishes in the background; read the account again until it does.
  useEffect(() => {
    if (!flow?.pending) return;
    const t = setInterval(() => store.getState().refresh(), 3000);
    return () => clearInterval(t);
  }, [flow?.pending, store]);
  return (
    <div className="mt-2 space-y-1.5 text-[13px]">
      {shown && (
        <RefusalBanner
          text={shown}
          onDismiss={() => {
            store.getState().clearRefusal();
            answers.getState().clearRefusal();
          }}
        />
      )}
      {flow && (
        <div className="space-y-1" role="status">
          <Chip tone={flow.tone}>{flow.label}</Chip>
          {flow.detail && <p className="text-xs text-muted-foreground">{flow.detail}</p>}
          {flow.code && <p className="font-mono text-base font-semibold tracking-widest">{flow.code}</p>}
        </div>
      )}
      {p.signedIn ? (
        <Button size="sm" variant="outline" onClick={() => store.getState().send("account_sign_out", signOutBody(p.id))}>
          Sign out
        </Button>
      ) : (
        <Button size="sm" disabled={!!flow?.pending} onClick={start}>
          {flow && !flow.pending ? "Try again" : "Sign in"}
        </Button>
      )}
    </div>
  );
}

/** The services that sign in through the account (Kotlin's Services tab, Infantry_Account). */
export function ServicesTab() {
  const store = accountStore();
  useQueryOnOpen(store);
  const data = useQuery(store, (s) => s.data);
  const services = list(data, "services");
  const providers = accountProviders(data);
  return (
    <TabPage title="Services" description="Runner, orchestrator and web hosting: services you sign in to" actions={<RefreshButton store={store} />}>
      <div className="mb-3">
        <AccountPanel quiet />
      </div>
      <Served store={store} banner={false} frames="account_status (account.status, with its services)">
        {providers.length > 0 && (
          <div className="mb-3 grid gap-3 lg:grid-cols-2">
            {providers.map((p) => (
              <PanelCard key={p.id} title={p.name} action={<Chip tone={p.signedIn ? "green" : "neutral"}>{p.signedIn ? "signed in" : "signed out"}</Chip>}>
                {p.description && <p className="text-xs text-muted-foreground">{p.description}</p>}
                {p.signedIn && (
                  <p className="text-xs">
                    {p.email}
                    {p.email ? " · " : ""}
                    <Chip mono>{p.plan ?? "no plan"}</Chip>
                  </p>
                )}
                {p.error && <p className="text-xs text-destructive">{p.error}</p>}
                <SignInOut p={p} />
              </PanelCard>
            ))}
          </div>
        )}
        {services.length === 0 ? (
          <EmptyState icon={<Server className="size-6" />} title={data ? "No service registered" : "Reading services…"} />
        ) : (
          <div className="grid gap-3 lg:grid-cols-2">
            {services.map((s, i) => (
              <PanelCard key={str(s, "id") ?? i} title={str(s, "name") ?? str(s, "id") ?? "service"} action={<Chip tone={str(s, "state") === "signed_in" ? "green" : "neutral"}>{str(s, "state") ?? "?"}</Chip>}>
                {str(s, "description") && <p className="text-xs text-muted-foreground">{str(s, "description")}</p>}
              </PanelCard>
            ))}
          </div>
        )}
      </Served>
    </TabPage>
  );
}
