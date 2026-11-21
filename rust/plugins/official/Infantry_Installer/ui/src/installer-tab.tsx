// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Apps tab (INSTALLER-1, APPS-ROOT-1): the apps a source offers and the apps installed, as bordered rows with Install and
// Remove at app level. Installing an app brings its core plugins; removing it takes its plugins away. An app's optional
// plugins sit under it with an Install switch. The installer is listed as a system app (no Remove), and a plugin that belongs
// to no app is under "Other plugins" so nothing is out of reach. The plugins of an app are managed inside the app, in its
// Plugins tab. The same view is the app's whole window while nothing else is installed.
// Built from the kit only: TabPage, ListRow, Switch, Button, EmptyState, RefusalBanner.
import { useEffect, useState } from "react";
import { ArrowLeft, Download, PackageOpen, Power, RefreshCw, Trash2 } from "lucide-react";
import { Button, EmptyState, ListRow, RefusalBanner, Switch, TabPage, type Tone } from "@infantry/ui";
import { useLinkState } from "@infantry/ui-plugin-api";
import { findApp, groupRows, installerStore, membersOf, routeApp, rowActions, useInstaller, type PluginRow } from "./store";

function tone(r: PluginRow): Tone {
  if (!r.installed) return "neutral";
  if (r.status.startsWith("refused") || r.status.startsWith("failed")) return "red";
  return r.enabled ? "green" : "amber";
}

function badge(r: PluginRow): string {
  if (!r.installed) return "available";
  return r.enabled ? "installed" : "disabled";
}

function subtitle(r: PluginRow): string {
  const needs = r.requires.length > 0 ? ` · needs ${r.requires.join(", ")}` : "";
  return `${r.description || r.id}${needs}`;
}

function Row({ r, busy, system, extra }: { r: PluginRow; busy: boolean; system?: boolean; extra?: string }) {
  const can = rowActions(r);
  const s = installerStore().getState();
  return (
    <ListRow
      row={{
        id: r.id,
        title: r.displayName || r.id,
        subtitle: extra ? `${r.description || r.id} · ${extra}` : subtitle(r),
        meta: r.version ? `v${r.version}` : undefined,
        tone: system ? "neutral" : tone(r),
        badge: system ? "system" : badge(r),
      }}
      icon={<PackageOpen className="size-4" />}
      trailing={
        <div className="flex shrink-0 items-center gap-1">
          {can.install && (
            <Button size="sm" disabled={busy} onClick={() => s.install([r.id])}>
              <Download /> Install
            </Button>
          )}
          {can.toggle && !r.app && (
            <Button size="sm" variant="outline" disabled={busy} onClick={() => s.setEnabled(r.id, !r.enabled)}>
              <Power /> {r.enabled ? "Disable" : "Enable"}
            </Button>
          )}
          {can.uninstall && (
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => s.uninstall(r.id)} aria-label={`Remove ${r.id}`}>
              <Trash2 /> Remove
            </Button>
          )}
        </div>
      }
    />
  );
}

function OptionalRow({ m, busy }: { m: PluginRow; busy: boolean }) {
  const s = installerStore().getState();
  const name = m.displayName || m.id;
  return (
    <div className="ml-8 flex items-center gap-3 rounded-lg border bg-card px-3 py-2" data-testid={`optional-${m.id}`}>
      <div className="min-w-0 flex-1">
        <div className="truncate text-sm font-medium">{name}</div>
        <div className="truncate text-xs text-muted-foreground">Optional · {m.description || m.id}</div>
      </div>
      <Switch
        checked={m.installed}
        disabled={busy || (!m.installed && m.source === null)}
        onCheckedChange={(on) => (on ? s.install([m.id]) : s.uninstall(m.id))}
        aria-label={`${m.installed ? "Remove" : "Install"} ${name}`}
      />
    </div>
  );
}

function AppEntry({ app, rows, busy }: { app: PluginRow; rows: PluginRow[]; busy: boolean }) {
  const m = membersOf(app, rows);
  const n = m.core.length;
  const extra = `includes ${n} plugin${n === 1 ? "" : "s"}${m.optional.length > 0 ? `, ${m.optional.length} optional` : ""}`;
  return (
    <div className="space-y-2" data-testid={`app-${app.id}`}>
      <Row r={app} busy={busy} extra={extra} />
      {app.installed && m.optional.map((o) => <OptionalRow key={o.id} m={o} busy={busy} />)}
    </div>
  );
}

/** A route opens one app's page (ARMORY-2: the taskbar's Updates item): that app's entry, and a way back to the whole list. */
function AppPage({ id, rows, busy, onBack }: { id: string; rows: PluginRow[]; busy: boolean; onBack: () => void }) {
  const row = findApp(rows, id);
  return (
    <div className="space-y-3" data-testid="app-page">
      <Button size="sm" variant="ghost" onClick={onBack}>
        <ArrowLeft /> All apps
      </Button>
      {row ? (
        row.app ? (
          <AppEntry app={row} rows={rows} busy={busy} />
        ) : (
          <Row r={row} busy={busy} system={row.bootstrap} />
        )
      ) : (
        <EmptyState icon={<PackageOpen className="size-6" />} title={`${id} is not in this list`} hint="It is not installed here, or no source offers it." />
      )}
    </div>
  );
}

export function InstallerTab({ route }: { route?: string }) {
  // The page a route names stays until the user goes back; a new route (another update picked) shows that page.
  const [left, setLeft] = useState<string | undefined>(undefined);
  useEffect(() => setLeft(undefined), [route]);
  const pageId = left === route ? null : routeApp(route);
  const link = useLinkState();
  const { rows, loaded, busy, refusal, notice } = useInstaller((s) => s);
  useEffect(() => {
    if (link === "open") installerStore().getState().refresh();
  }, [link]);
  const s = installerStore().getState();
  const groups = groupRows(rows);
  const nothingElse = groups.apps.every((r) => !r.installed);
  return (
    <TabPage
      title="Apps"
      description={nothingElse ? "No app is installed yet. Install an app; it loads, with its plugins, as soon as it is in." : "Install and remove apps. An app's plugins are managed inside the app."}
      actions={
        <Button size="sm" variant="outline" disabled={busy !== null} onClick={() => s.refresh()}>
          <RefreshCw /> Refresh
        </Button>
      }
    >
      <RefusalBanner text={refusal} onDismiss={() => s.clearRefusal()} />
      {busy && (
        <p className="mb-3 text-xs text-muted-foreground" role="status">
          {busy}…
        </p>
      )}
      {!busy && notice && (
        <p className="mb-3 text-xs text-muted-foreground" role="status">
          {notice}
        </p>
      )}
      {!loaded ? (
        <p className="text-xs text-muted-foreground">Reading the app list…</p>
      ) : pageId ? (
        <AppPage id={pageId} rows={rows} busy={busy !== null} onBack={() => setLeft(route)} />
      ) : rows.length === 0 ? (
        <EmptyState icon={<PackageOpen className="size-6" />} title="No apps to install" hint="No source offers one. Set the bundled folder (plugins.bundled) or add a folder to plugins.sources." />
      ) : (
        <div className="space-y-4">
          <section className="space-y-2" aria-label="Apps">
            {groups.apps.map((a) => (
              <AppEntry key={a.id} app={a} rows={rows} busy={busy !== null} />
            ))}
            {groups.system.map((r) => (
              <Row key={r.id} r={r} busy={busy !== null} system />
            ))}
          </section>
          {groups.other.length > 0 && (
            <section className="space-y-2" aria-label="Other plugins">
              <h2 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Other plugins</h2>
              {groups.other.map((r) => (
                <Row key={r.id} r={r} busy={busy !== null} />
              ))}
            </section>
          )}
        </div>
      )}
    </TabPage>
  );
}
