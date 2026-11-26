// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// MCP, Review, Account, Sites and Services (RUST-UI-7), drawn from the daemon's frames like the Kotlin panels. Each panel
// asks its frame on open; a daemon with no unit for it answers a refusal, which the panel shows as "not served" (Served).
//   MCP      `mcp_list` -> servers (state, tools, enabled), grants; `mcp_set_enabled` / `mcp_test` / `mcp_revoke`
//   Review   the converged pods' nodes waiting for review (`pod_query` / `pod` pushes)
//   Account  `account_status` (Infantry_Account's `account.status`); Services lists its account providers
//   Sites    `sites_list` (Infantry_Sites' `infantry_sites_list`): hosting servers, read-only
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ChevronLeft, Globe, GitPullRequest, Plug, Server } from "lucide-react";
import { accountProviders, accountState, cardChip, SEPARATE_SIGN_IN_TEXT, flowView, hubView, NO_PROVIDERS_TEXT, servicePages, shouldOpenApproval, signOutConfirm, planNotice, type ServicePage, manageUrl, serverStatus, settingKey, shownAnswer, siteDate, sitesSignIn, SITES_REFRESH_DEFAULT, SITES_REFRESH_KEY, SITES_REFRESH_NODE, startAutoRefresh, reviewQueue, signInBody, signInTracker, signInUri, signOutBody, type AccountProvider, type McpServerView } from "@infantry/client";
import { AppDialog, Button, Chip, EmptyState, PanelCard, Switch } from "@infantry/ui";
import { useDaemon } from "@/daemon/daemon";
import { TabPage } from "@/components/tabs/tab-page";
import { ago, RefusalBanner } from "@/components/knowledge/parts";
import { RefreshButton, Served } from "./parts";
import { host } from "@/host";
import { settingsStore, useSettings } from "@/components/settings/use-settings";
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

/** The refresh period (seconds) from the `sites.refreshSeconds` setting; its declared default until the settings load. */
function useRefreshSeconds(): number {
  const values = useSettings((s) => s.values);
  useEffect(() => settingsStore().getState().ensureLoaded(), []);
  const v = values[settingKey(SITES_REFRESH_NODE, SITES_REFRESH_KEY)];
  return typeof v === "number" && v >= 0 ? v : SITES_REFRESH_DEFAULT;
}

/** Hosting servers: follows the server's state on its own (no create, start, stop or delete here: those are on the website).
 * [p] is the provider this page belongs to: its sign-in state is the truth the page shows (the same words as the card). */
export function SitesPanel({ p }: { p?: AccountProvider }) {
  const store = probeStore("sites_list");
  useQueryOnOpen(store);
  const live = useQuery(store, (s) => s.data);
  const refusal = useQuery(store, (s) => s.refusal);
  const seconds = useRefreshSeconds();
  const [good, setGood] = useState<{ data: Record<string, unknown>; at: number } | null>(null);
  const [, tickAge] = useState(0);
  // The last answer that was not a failure stays on screen when a later refresh fails.
  useEffect(() => {
    if (live && !refusal && str(live, "state") !== "unavailable") setGood({ data: live, at: Date.now() });
  }, [live, refusal]);
  useEffect(() => {
    const visible = () => document.visibilityState !== "hidden";
    const auto = startAutoRefresh(store, { seconds, visible });
    document.addEventListener("visibilitychange", auto.poke);
    const age = setInterval(() => tickAge((n) => n + 1), 15000);
    return () => {
      auto.stop();
      clearInterval(age);
      document.removeEventListener("visibilitychange", auto.poke);
    };
  }, [store, seconds]);
  const { data, error, stale } = shownAnswer(live, refusal, good?.data ?? null);
  const servers = list(data, "servers");
  const signIn = sitesSignIn(p?.signIn ?? null, live, refusal);
  // The sign-in line replaces the plugin's own signed_out / unavailable chip: one state, never two.
  const account = signIn ? null : accountState(data);
  const url = manageUrl(data);
  const reclaim = siteDate(data?.reclaimableUntil);
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">Your hosting servers</span>
        <span className="flex items-center gap-1">
          {url && (
            <Button variant="ghost" size="xs" onClick={() => void host.openExternal(url).catch(() => undefined)}>
              Manage on website
            </Button>
          )}
          <RefreshButton store={store} />
        </span>
      </div>
      {signIn && (
        <div className="space-y-1.5 text-[13px]" role="status" aria-label="Sign-in state">
          <div className="flex items-center gap-2">
            <Chip tone={signIn.tone}>{signIn.label}</Chip>
            {signIn.action === "retry" && (
              <Button
                size="xs"
                variant="outline"
                onClick={() => {
                  accountStore().getState().refresh();
                  store.getState().refresh();
                }}
              >
                Retry
              </Button>
            )}
          </div>
          {signIn.text && <p className="text-xs text-muted-foreground">{signIn.text}</p>}
          {/* The page asks for a sign-in: the card's device flow, even when the daemon still lists one (a refused token). */}
          {signIn.action === "sign_in" && p && <SignIn p={{ ...p, signedIn: false }} />}
        </div>
      )}
      {stale && error && !signIn && (
        <p role="status" className="rounded-lg border border-destructive/40 p-2 text-xs text-destructive">
          Could not refresh: {error}. Showing the list from {good ? ago(good.at) : "earlier"}.
        </p>
      )}
      <Served store={store} frames="sites_list (Infantry_Sites state)" banner={!stale}>
        {!data ? (
          <EmptyState icon={<Globe className="size-6" />} title="Reading sites…" />
        ) : (
          <>
            {account && (
              <div className="flex items-center gap-2 text-[13px]" role="status">
                <Chip tone={account.tone}>{account.label}</Chip>
                {account.text && <span>{account.text}</span>}
              </div>
            )}
            {reclaim && <p className="text-xs text-muted-foreground">Reclaimable until {reclaim}</p>}
            {servers.length === 0 ? (
              !account && !signIn && <EmptyState icon={<Globe className="size-6" />} title={str(data, "message") ?? "No servers"} />
            ) : (
              <ul className="space-y-2">
                {servers.map((sv, i) => {
                  const st = serverStatus(str(sv, "status"));
                  const destroyAt = siteDate(sv.destroyAt);
                  return (
                    <li key={str(sv, "id") ?? i} className="rounded-lg border p-2 text-[13px]">
                      <div className="flex items-center gap-2">
                        <span className="min-w-0 flex-1 truncate font-medium">{str(sv, "name") ?? str(sv, "id")}</span>
                        <Chip tone={st.tone}>{st.label}</Chip>
                      </div>
                      <p className="font-mono text-xs text-muted-foreground">{[str(sv, "ip"), str(sv, "region"), str(sv, "size")].filter(Boolean).join(" · ")}</p>
                      {destroyAt && <p className="text-xs text-muted-foreground">Destroys on {destroyAt}</p>}
                    </li>
                  );
                })}
              </ul>
            )}
          </>
        )}
      </Served>
    </div>
  );
}

/** The seconds between re-reads of the account while the Services tab is open and visible: the app follows the daemon's state (a sign-out made elsewhere, a sign-in finishing). */
const SERVICES_FOLLOW_SECONDS = 5;

/** Sign in (or Try again) on a card that is not connected: starts the device-code flow and opens its approval page once per click. */
function SignIn({ p }: { p: AccountProvider }) {
  const store = accountStore();
  const answers = probeStore("account_sign_in");
  const flow = flowView(p);
  const answer = useQuery(answers, (s) => s.data);
  const rawRefusal = useQuery(answers, (s) => s.refusal);
  // The answer store is shared by every card and lives longer than this card: only a click made on THIS mounted card opens a page.
  const code = signInTracker.owns(p.id, answer) ? answer : null;
  const signInRefusal = signInTracker.owns(p.id) ? rawRefusal : null;
  const refusal = useQuery(store, (s) => s.refusal);
  const shown = refusal ?? signInRefusal;
  const clickedHere = useRef(false);
  const opened = useRef<string | null>(null);
  const start = () => {
    clickedHere.current = true;
    opened.current = signInUri(code); // an answer already in the store belongs to an earlier sign-in
    signInTracker.start(p.id);
    answers.getState().clearRefusal();
    answers.getState().refresh(signInBody(p.id));
  };
  useEffect(() => {
    const uri = signInUri(code);
    if (shouldOpenApproval({ clickedHere: clickedHere.current, signedIn: p.signedIn, uri, alreadyOpened: opened.current })) {
      opened.current = uri;
      void host.openExternal(uri!).catch(() => undefined);
    }
  }, [code, p.signedIn]);
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
          {flow.pending && p.flow?.verificationUri && (
            <Button variant="link" size="sm" className="h-auto p-0" onClick={() => void host.openExternal(p.flow!.verificationUri!).catch(() => undefined)}>
              Open the approval page again
            </Button>
          )}
        </div>
      )}
      <Button size="sm" disabled={!!flow?.pending} onClick={start}>
        {flow && !flow.pending ? "Try again" : "Sign in"}
      </Button>
    </div>
  );
}

/** A signed-in account without an active plan: says so and links to the provider's plans page. */
function PlanNotice({ p, boxed }: { p: AccountProvider; boxed?: boolean }) {
  const notice = planNotice(p);
  if (!notice) return null;
  return (
    <p role="status" className={boxed ? "rounded-lg border p-2 text-xs" : "text-xs text-muted-foreground"}>
      {notice}{" "}
      {p.billingUrl && (
        <Button variant="link" size="sm" className="h-auto p-0 text-xs" onClick={() => void host.openExternal(p.billingUrl!).catch(() => undefined)}>
          See the plans
        </Button>
      )}
    </p>
  );
}

/** One provider card of the list. A connected card opens its dashboard; the others offer Sign in. */
function ProviderCard({ p, onOpen }: { p: AccountProvider; onOpen: () => void }) {
  const chip = cardChip(p);
  const fromButton = (t: EventTarget) => t instanceof Element && !!t.closest("button, a, input");
  return (
    <div
      {...(p.signedIn
        ? {
            role: "button",
            tabIndex: 0,
            "aria-label": `Open ${p.name}`,
            className: "cursor-pointer rounded-xl transition-colors hover:bg-muted/40 focus-visible:outline focus-visible:outline-2 focus-visible:outline-ring",
            onClick: (e: React.MouseEvent) => {
              if (!fromButton(e.target)) onOpen();
            },
            onKeyDown: (e: React.KeyboardEvent) => {
              if ((e.key === "Enter" || e.key === " ") && e.target === e.currentTarget) {
                e.preventDefault();
                onOpen();
              }
            },
          }
        : {})}
    >
    <PanelCard title={p.name} action={<Chip tone={chip.tone}>{chip.label}</Chip>}>
      {p.description && <p className="text-xs text-muted-foreground">{p.description}</p>}
      {p.signedIn && p.plan && (
        <p className="text-xs">
          <Chip mono>{p.plan}</Chip>
        </p>
      )}
      <PlanNotice p={p} />
      {p.error && <p className="text-xs text-destructive">{p.error}</p>}
      {!p.signedIn && p.signIn === "expired" && <p className="text-xs text-muted-foreground">{SEPARATE_SIGN_IN_TEXT}</p>}
      {p.signedIn ? (
        <Button className="mt-2" size="sm" onClick={onOpen} aria-label={`Open ${p.name}`}>
          Open
        </Button>
      ) : (
        <SignIn p={p} />
      )}
    </PanelCard>
    </div>
  );
}

/** What a dashboard page (a service's `section`) draws. The one place the app names a section. */
const SECTION_VIEWS: Record<string, (p: AccountProvider) => ReactNode> = {
  "infantry.sites": (p) => <SitesPanel p={p} />,
};

/** A connected service's dashboard inside the Services tab: back, name, chip, plan notice, Sign out, a left menu (hidden with one page) and the page. */
function ServiceDashboard({ p, pages, onBack }: { p: AccountProvider; pages: ServicePage[]; onBack: () => void }) {
  const store = accountStore();
  const [page, setPage] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);
  const shown = pages.find((x) => x.id === page) ?? pages[0];
  const chip = cardChip(p);
  const confirm = signOutConfirm(p.name);
  const notice = planNotice(p);
  const body = notice ? <EmptyState icon={<Server className="size-6" />} title={notice} /> : shown ? (SECTION_VIEWS[shown.section]?.(p) ?? <EmptyState icon={<Server className="size-6" />} title={`${shown.name} has no page in this app`} />) : <EmptyState icon={<Server className="size-6" />} title="This service has no pages yet" />;
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="ghost" size="sm" onClick={onBack}>
          <ChevronLeft className="size-4" /> Services
        </Button>
        <span className="text-sm font-semibold">{p.name}</span>
        <Chip tone={chip.tone}>{chip.label}</Chip>
        {p.plan && <Chip mono>{p.plan}</Chip>}
        {p.signedIn && (
          <Button className="ml-auto" size="sm" variant="outline" onClick={() => setConfirming(true)}>
            Sign out
          </Button>
        )}
      </div>
      {notice && <PlanNotice p={p} boxed />}
      <div className="flex gap-4">
        {pages.length > 1 && (
          <nav aria-label={`${p.name} pages`} className="w-40 shrink-0 space-y-1">
            {pages.map((x) => (
              <Button key={x.id} size="sm" variant={x.id === shown?.id ? "secondary" : "ghost"} className="w-full justify-start" onClick={() => setPage(x.id)}>
                {x.name}
              </Button>
            ))}
          </nav>
        )}
        <div className="min-w-0 flex-1">{body}</div>
      </div>
      <AppDialog
        open={confirming}
        onOpenChange={setConfirming}
        title={confirm.title}
        description={confirm.body}
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirming(false)}>
              Cancel
            </Button>
            <Button
              onClick={() => {
                setConfirming(false);
                store.getState().send("account_sign_out", signOutBody(p.id));
              }}
            >
              Sign out
            </Button>
          </>
        }
      >
        <p className="text-sm">{confirm.body}</p>
      </AppDialog>
    </div>
  );
}

/** Services: a list of providers (one card each); a connected card opens its dashboard inside this tab. */
export function ServicesTab() {
  const store = accountStore();
  useQueryOnOpen(store);
  const data = useQuery(store, (s) => s.data);
  const providers = accountProviders(data);
  const [openId, setOpenId] = useState<string | null>(null);
  // The app follows the daemon: re-read while the tab is open and visible (a sign-out made elsewhere shows within seconds).
  useEffect(() => {
    const auto = startAutoRefresh(store, { seconds: SERVICES_FOLLOW_SECONDS, visible: () => document.visibilityState !== "hidden" });
    document.addEventListener("visibilitychange", auto.poke);
    return () => {
      auto.stop();
      document.removeEventListener("visibilitychange", auto.poke);
    };
  }, [store]);
  const view = hubView(providers, openId);
  return (
    <TabPage title="Services" description="Services you sign in to" actions={<RefreshButton store={store} />}>
      <Served store={store} banner frames="account_status (account.status, with its services)">
        {view.kind === "dashboard" ? (
          <ServiceDashboard p={view.provider} pages={servicePages(data)} onBack={() => setOpenId(null)} />
        ) : providers.length === 0 ? (
          <EmptyState icon={<Server className="size-6" />} title={data ? NO_PROVIDERS_TEXT : "Loading…"} />
        ) : (
          <div className="grid gap-3 lg:grid-cols-2">
            {providers.map((p) => (
              <ProviderCard key={p.id} p={p} onOpen={() => setOpenId(p.id)} />
            ))}
          </div>
        )}
      </Served>
    </TabPage>
  );
}
