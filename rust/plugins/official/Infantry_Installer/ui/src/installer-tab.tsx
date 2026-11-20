// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The Apps tab (INSTALLER-1): every plugin a source offers and every plugin installed, as bordered rows with their state,
// what they need, and Install / Remove / Enable. "Install all" sends everything not installed in one request and the daemon
// puts each module after the ones it requires. The same view is the app's whole window while nothing else is installed.
// Built from the kit only: TabPage, ListRow, Chip, Button, EmptyState, RefusalBanner.
import { useEffect } from "react";
import { Download, PackageOpen, Power, RefreshCw, Trash2 } from "lucide-react";
import { Button, EmptyState, ListRow, RefusalBanner, TabPage, type Tone } from "@infantry/ui";
import { useLinkState } from "@infantry/ui-plugin-api";
import { installableIds, installerStore, rowActions, useInstaller, type PluginRow } from "./store";

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

function Row({ r, busy }: { r: PluginRow; busy: boolean }) {
  const can = rowActions(r);
  const s = installerStore().getState();
  return (
    <ListRow
      row={{ id: r.id, title: r.displayName || r.id, subtitle: subtitle(r), meta: r.version ? `v${r.version}` : undefined, tone: tone(r), badge: badge(r) }}
      icon={<PackageOpen className="size-4" />}
      trailing={
        <div className="flex shrink-0 items-center gap-1">
          {can.install && (
            <Button size="sm" disabled={busy} onClick={() => s.install([r.id])}>
              <Download /> Install
            </Button>
          )}
          {can.toggle && (
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

export function InstallerTab() {
  const link = useLinkState();
  const { rows, loaded, busy, refusal, notice } = useInstaller((s) => s);
  useEffect(() => {
    if (link === "open") installerStore().getState().refresh();
  }, [link]);
  const s = installerStore().getState();
  const todo = installableIds(rows);
  const nothingElse = rows.every((r) => r.bootstrap || !r.installed);
  return (
    <TabPage
      title="Apps"
      description={nothingElse ? "Nothing else is installed yet. Install the plugins you want; they load as soon as they are in." : "Install, enable and remove plugins."}
      actions={
        <>
          <Button size="sm" variant="outline" disabled={busy !== null} onClick={() => s.refresh()}>
            <RefreshCw /> Refresh
          </Button>
          {todo.length > 0 && (
            <Button size="sm" disabled={busy !== null} onClick={() => s.install(todo)}>
              <Download /> Install all ({todo.length})
            </Button>
          )}
        </>
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
        <p className="text-xs text-muted-foreground">Reading the plugin list…</p>
      ) : rows.length === 0 ? (
        <EmptyState icon={<PackageOpen className="size-6" />} title="No plugins to install" hint="No source offers one. Set the bundled folder (plugins.bundled) or add a folder to plugins.sources." />
      ) : (
        <div className="space-y-2">
          {rows.map((r) => (
            <Row key={r.id} r={r} busy={busy !== null} />
          ))}
        </div>
      )}
    </TabPage>
  );
}
