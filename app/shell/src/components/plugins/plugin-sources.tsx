// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PLUGINS-2: the plugin manager's source list, the cross-source search results, and the approval and uninstall dialogs.
// All driven by the plugins store (`plugin_admin`); the cards, the one modal (AppDialog) and the fields are the shared ones.
import { useMemo, useState } from "react";
import { Download, FolderGit2, Puzzle, RefreshCw, Trash2 } from "lucide-react";
import { searchCatalog, type CatalogRow, type PluginRow, type PluginSource, type PluginUpdate } from "@infantry/client";
import { AppDialog, Button, Chip, EmptyState, Field, IconButton, Input } from "@infantry/ui";
import { pluginsStore } from "@/plugins/use-plugins";

/** What the user is asked to approve: a new install, or an update of an installed plugin. */
export type Approval = { kind: "install"; row: CatalogRow } | { kind: "update"; row: PluginRow; update: PluginUpdate; catalog?: CatalogRow };

/** What the daemon's check of an offered copy means, in words the owner can decide on. */
export function signerText(signed: string): { tone: "green" | "red" | "neutral"; text: string; needsTrust: boolean } {
  switch (signed) {
    case "signed":
      return { tone: "green", text: "Signed by a trusted signer", needsTrust: false };
    case "untrusted":
      return { tone: "red", text: "Signed by a key that is not a trusted signer", needsTrust: true };
    case "digest":
      return { tone: "neutral", text: "Unsigned; its files match the published checksum", needsTrust: false };
    case "bad":
      return { tone: "red", text: "The check failed; the daemon will refuse it", needsTrust: false };
    default:
      return { tone: "neutral", text: "Unsigned", needsTrust: false };
  }
}

/** Installing or updating: signer, licence, what it needs and where it comes from, then a confirm. */
export function ApprovalDialog({ a, onClose }: { a: Approval; onClose: () => void }) {
  const [trust, setTrust] = useState(false);
  const row = a.kind === "install" ? a.row : a.catalog;
  const name = a.kind === "install" ? a.row.displayName : (a.row.ui?.displayName ?? a.row.id);
  const id = a.row.id;
  const up = a.kind === "update" ? a.update : undefined;
  const sig = signerText(row?.signed || up?.signed || "");
  const licence = row?.license || up?.license;
  const version = a.kind === "install" ? a.row.version : a.update.available;
  const needs = row && row.grants.length > 0 ? row.grants : up && up.grants.length > 0 ? up.grants : (row?.requires ?? []);
  const verb = a.kind === "install" ? "Install" : "Update";
  const go = () => {
    const store = pluginsStore().getState();
    if (a.kind === "install") store.install(id, trust);
    else store.update(id, trust);
    onClose();
  };
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`${verb} ${name}`}
      description={`${id}${version ? ` · v${version}` : ""}`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={go} disabled={sig.needsTrust && !trust} aria-label={`Confirm ${verb.toLowerCase()} ${name}`}>
            {verb}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3 text-[13px]">
        {row?.description && <p className="leading-5 text-muted-foreground">{row.description}</p>}
        <Field id="approve-signer" label="Signer">
          <Chip tone={sig.tone}>{sig.text}</Chip>
        </Field>
        <Field id="approve-licence" label="Licence">
          <span>{licence || "Not stated"}</span>
        </Field>
        <Field id="approve-needs" label="Needs" hint="What the plugin asks of this host">
          <div className="flex flex-wrap gap-1.5">
            {needs.length === 0 ? (
              <span className="text-muted-foreground">Nothing beyond its own files</span>
            ) : (
              needs.map((g) => (
                <Chip key={g} mono>
                  {g}
                </Chip>
              ))
            )}
          </div>
        </Field>
        <Field id="approve-source" label="From">
          <span className="break-all font-mono text-xs">{(a.kind === "update" ? a.update.source : row?.source) || "unknown"}</span>
        </Field>
        {sig.needsTrust && (
          <label className="flex items-start gap-2 rounded-md border border-danger/60 p-2">
            <input type="checkbox" className="mt-0.5" checked={trust} onChange={(e) => setTrust(e.target.checked)} />
            <span>I trust this signer and want to {verb.toLowerCase()} it anyway</span>
          </label>
        )}
      </div>
    </AppDialog>
  );
}

export function UninstallDialog({ p, onClose }: { p: PluginRow; onClose: () => void }) {
  const name = p.ui?.displayName ?? p.id;
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Uninstall ${name}?`}
      description="Its files are removed from this host."
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            aria-label={`Confirm uninstall ${name}`}
            onClick={() => {
              pluginsStore().getState().uninstall(p.id);
              onClose();
            }}
          >
            Uninstall
          </Button>
        </>
      }
    >
      <p className="text-[13px] text-muted-foreground">You can install it again from Browse.</p>
    </AppDialog>
  );
}

/** The sources of this host: one card each (folder or git) with refresh and remove, and a field to add one. */
export function SourcesPanel({ sources, pending }: { sources: PluginSource[]; pending: string[] }) {
  const [where, setWhere] = useState("");
  const add = () => {
    if (!where.trim()) return;
    pluginsStore().getState().addSource(where);
    setWhere("");
  };
  return (
    <div className="flex flex-col gap-3">
      <form
        className="flex flex-wrap items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          add();
        }}
      >
        <Field id="source-location" label="Add a source" hint="A folder on this host, or a git repository URL">
          <Input id="source-location" className="h-8 w-80 max-w-full text-xs" placeholder="A folder path or a git URL" value={where} onChange={(e) => setWhere(e.target.value)} />
        </Field>
        <Button type="submit" size="sm" disabled={!where.trim()}>
          Add source
        </Button>
      </form>
      {sources.length === 0 ? (
        <EmptyState icon={<FolderGit2 className="size-6" />} title="No sources yet" />
      ) : (
        <div className="grid grid-cols-[repeat(auto-fill,minmax(280px,1fr))] gap-3">
          {sources.map((s) => (
            <article key={s.id} className="flex flex-col gap-2 rounded-lg border bg-card p-4" aria-label={`Source ${s.id}`}>
              <div className="flex items-start gap-2">
                <div className="min-w-0 flex-1">
                  <h3 className="truncate font-semibold">{s.id}</h3>
                  <p className="truncate font-mono text-[11px] text-muted-foreground" title={s.location}>
                    {s.location}
                  </p>
                </div>
                <IconButton label={`Refresh ${s.id}`} disabled={pending.includes(s.id)} onClick={() => pluginsStore().getState().refreshSource(s.id)}>
                  <RefreshCw />
                </IconButton>
                <IconButton label={`Remove ${s.id}`} disabled={pending.includes(s.id)} onClick={() => pluginsStore().getState().removeSource(s.id)}>
                  <Trash2 />
                </IconButton>
              </div>
              <div className="flex flex-wrap gap-2">
                <Chip tone="neutral">{s.kind}</Chip>
                <Chip tone={s.offers < 0 ? "red" : "neutral"}>{s.offers < 0 ? "folder missing" : `${s.offers} plugin${s.offers === 1 ? "" : "s"}`}</Chip>
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}

/** Search across every source: what is offered and not installed yet, each card with an Install button. */
export function BrowseGrid({ rows, query, pending, onApprove }: { rows: CatalogRow[]; query: string; pending: string[]; onApprove: (a: Approval) => void }) {
  const hits = useMemo(() => searchCatalog(rows, query).filter((r) => !r.installed), [rows, query]);
  if (hits.length === 0) return <EmptyState icon={<Download className="size-6" />} title={query.trim() ? "No plugin matches in any source" : "Nothing to install: add a source to see more"} />;
  return (
    <div className="grid grid-cols-[repeat(auto-fill,minmax(280px,1fr))] gap-3">
      {hits.map((r) => (
        <article key={r.id} className="flex flex-col gap-3 rounded-lg border bg-card p-4" aria-label={r.displayName}>
          <div className="flex items-start gap-3">
            <div className="grid size-10 shrink-0 place-items-center rounded-md border bg-raised text-muted-foreground">
              <Puzzle className="size-5" />
            </div>
            <div className="min-w-0 flex-1">
              <h3 className="truncate font-semibold">{r.displayName}</h3>
              <p className="truncate text-xs text-muted-foreground">
                {r.id}
                {r.version ? ` · v${r.version}` : ""}
              </p>
            </div>
            <Button size="sm" disabled={pending.includes(r.id)} onClick={() => onApprove({ kind: "install", row: r })} aria-label={`Install ${r.displayName}`}>
              {pending.includes(r.id) ? "Installing…" : "Install"}
            </Button>
          </div>
          {r.description && <p className="text-[13px] leading-5 text-muted-foreground">{r.description}</p>}
          <div className="flex flex-wrap gap-2">
            <Chip tone={signerText(r.signed).tone}>{r.signed || "unsigned"}</Chip>
            {r.source && <Chip tone="neutral">{r.source.split(/[\\/]/).filter(Boolean).pop()}</Chip>}
          </div>
        </article>
      ))}
    </div>
  );
}
