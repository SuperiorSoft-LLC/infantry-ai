// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// WAKE-UI-2: who a ticket's comments notify, in the ticket dialog. Chips over the board's notify list (agent ids, session
// ids, `role:<name>`, `owner`), each with its own Wake / Inbox; the picker only ADDS (the creator is on the list from the
// start and is never removed here). Reads `infantry_ticket_notify`, writes `infantry_ticket_update` addNotify/removeNotify.
import { useCallback, useEffect, useState } from "react";
import { X } from "lucide-react";
import { callTool, roleList } from "@infantry/client";
import { Chip, Label, Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@infantry/ui";
import { daemonStore, useDaemon } from "@/daemon/daemon";
import { useRoles, useRolesLoaded } from "@/components/knowledge/use-knowledge";
import { registryStore, useQuery } from "@/components/panels/use-panels";

const INBOX = "#inbox";
const ADD = "__add__";
const split = (e: string): { target: string; inbox: boolean } => (e.endsWith(INBOX) ? { target: e.slice(0, -INBOX.length), inbox: true } : { target: e, inbox: false });

export function TicketNotify({ ticketId }: { ticketId: string }) {
  const state = useDaemon((s) => s.connection.state);
  const sessions = useDaemon((s) => s.sessions);
  const agents = useQuery(registryStore(), (s) => s.data);
  useRolesLoaded();
  const roles = useRoles((s) => s.roles);
  const [list, setList] = useState<string[] | null>(null);
  const [createdBy, setCreatedBy] = useState("");
  const [error, setError] = useState<string | null>(null);
  const load = useCallback(async () => {
    const r = await callTool(daemonStore().getState(), "infantry_ticket_notify", { ticketId });
    if (r.isError) return setError(r.text);
    try {
      const j = JSON.parse(r.text) as { createdBy?: string; notify?: string[] };
      setError(null);
      setCreatedBy(j.createdBy ?? "");
      setList(j.notify ?? []);
    } catch {
      setError(r.text);
    }
  }, [ticketId]);
  useEffect(() => {
    if (state === "open") void load();
  }, [state, load]);
  const edit = async (args: Record<string, unknown>) => {
    const r = await callTool(daemonStore().getState(), "infantry_ticket_update", { ticketId, ...args });
    if (r.isError) setError(r.text);
    await load();
  };
  const listed = new Set((list ?? []).map((e) => split(e).target));
  const options: { value: string; label: string }[] = [
    { value: "owner", label: "Owner (app notice)" },
    ...roleList(roles).map((r) => ({ value: `role:${r.id}`, label: `Role: ${r.name}` })),
    ...agents.filter((a) => a.enabled).map((a) => ({ value: a.definition.id, label: `Agent: ${a.definition.name ?? a.definition.id}` })),
    ...Object.values(sessions)
      .filter((v) => !["EXITED", "FAILED", "ORPHANED"].includes(v.record.state))
      .map((v) => ({ value: v.record.id, label: `Session: ${v.record.role ?? v.record.agentId ?? "session"} ${v.record.id.slice(-8)}` })),
  ].filter((o) => !listed.has(o.value));
  const label = (target: string): string => options.find((o) => o.value === target)?.label ?? (target.startsWith("role:") ? `Role: ${target.slice(5)}` : target);
  return (
    <div className="space-y-1.5">
      <Label>Notify on comments</Label>
      <div className="flex flex-wrap items-center gap-2">
        {(list ?? []).map((e) => {
          const { target, inbox } = split(e);
          const isCreator = target === createdBy;
          return (
            <Chip key={e} tone={inbox ? "neutral" : "amber"}>
              <span>{label(target)}</span>
              <button
                type="button"
                title={inbox ? "Inbox: no turn. Click for Wake" : "Wake: starts a turn. Click for Inbox"}
                className="ml-1 rounded border px-1 text-[10px]"
                onClick={() => void edit({ addNotify: [inbox ? target : `${target}${INBOX}`] })}
              >
                {inbox ? "Inbox" : "Wake"}
              </button>
              {!isCreator && (
                <button type="button" aria-label={`Remove ${target}`} className="ml-1" onClick={() => void edit({ removeNotify: [target] })}>
                  <X className="size-3" />
                </button>
              )}
            </Chip>
          );
        })}
        <Select value={ADD} onValueChange={(v) => v !== ADD && void edit({ addNotify: [v] })}>
          <SelectTrigger className="w-48" aria-label="Add a recipient">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value={ADD}>Add a recipient…</SelectItem>
            {options.map((o) => (
              <SelectItem key={o.value} value={o.value}>
                {o.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
      {error && (
        <p role="alert" className="text-xs text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
