// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Ticket detail / create, in the one AppDialog. Creating needs only a title; the type and the epic (the current
// project's epics, `epic_list`) sit under Advanced.
import { useEffect, useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { LANE_TITLES, type BoardTicket, type Lane } from "@infantry/client";
import { AppDialog, Button, Chip, Input, Label, Textarea } from "@infantry/ui";
import { AdvancedSection } from "@/components/dialogs/advanced-section";
import { INHERIT, PickerField } from "@/components/dialogs/picker-field";
import { TicketArchiveButton } from "./archive-dialog";
import { shortId } from "./ticket-card";
import { useBoard } from "./use-board";
import { TicketComments } from "./ticket-comments";
import { TicketNotify } from "./ticket-notify";
import { epicsStore, useQuery, useQueryOnOpen } from "@/components/panels/use-panels";
import { useProjects } from "@/components/git/use-projects";

/** TicketType in the Kotlin protocol. */
const TYPES = ["enhancement", "defect", "restructure", "upkeep", "documentation", "cosmetic", "performance", "testing", "hardening"];

const schema = z.object({
  title: z.string().trim().min(1, "A title is required"),
  description: z.string(),
  ticketType: z.string(),
  epicId: z.string(),
});
type Form = z.infer<typeof schema>;

export function TicketDialog({ ticketId, creating, onClose }: { ticketId: string | null; creating: boolean; onClose: () => void }) {
  const ticket: BoardTicket | undefined = useBoard((s) => (ticketId ? s.tickets[ticketId] : undefined));
  const save = useBoard((s) => s.save);
  const remove = useBoard((s) => s.remove);
  const [confirm, setConfirm] = useState(false);
  const project = useProjects((s) => s.currentId);
  const epicStore = epicsStore();
  useQueryOnOpen(epicStore, project ?? undefined, !project || !(creating || !!ticketId));
  const epics = useQuery(epicStore, (s) => s.data);
  const shownEpics = epics.filter((e) => !project || e.projectId === project);
  const epicNames = Object.fromEntries(epics.map((e) => [e.id, e.name]));
  const epicOptions = [...new Set([...shownEpics.map((e) => e.id), ...(ticket?.epicId ? [ticket.epicId] : [])])];
  const form = useForm<Form>({ resolver: zodResolver(schema), defaultValues: { title: "", description: "", ticketType: INHERIT, epicId: INHERIT } });
  useEffect(() => {
    setConfirm(false);
    form.reset({ title: ticket?.title ?? "", description: ticket?.description ?? "", ticketType: ticket?.ticketType ?? INHERIT, epicId: ticket?.epicId ?? INHERIT });
  }, [ticket?.id, ticket?.partial, creating]); // eslint-disable-line react-hooks/exhaustive-deps
  const loadFull = useBoard((s) => s.loadFull);
  useEffect(() => {
    if (ticket?.id) loadFull(ticket.id);
  }, [ticket?.id, loadFull]);
  const open = creating || !!ticket;
  const submit = form.handleSubmit((v) => {
    const type = v.ticketType === INHERIT ? (ticket?.ticketType ? "" : undefined) : v.ticketType;
    const epicId = v.epicId === INHERIT ? (ticket?.epicId ? "" : undefined) : v.epicId;
    save(
      ticket
        ? { id: ticket.id, title: v.title, description: v.description, ticketType: type, epicId }
        : { title: v.title, description: v.description || undefined, ticketType: type, epicId },
    );
    onClose();
  });
  const err = form.formState.errors.title?.message;
  return (
    <AppDialog
      open={open}
      wide
      onOpenChange={(o) => !o && onClose()}
      title={ticket ? `Ticket ${shortId(ticket.id)}` : "New ticket"}
      description={ticket ? "Edit the ticket. Changes save to the daemon." : "Only a title is needed. Everything else inherits."}
      footer={
        <>
          {ticket && (
            <Button
              variant="ghost"
              className="mr-auto text-danger"
              onClick={() => (confirm ? (remove(ticket.id), onClose()) : setConfirm(true))}
            >
              {confirm ? "Confirm delete" : "Delete"}
            </Button>
          )}
          {ticket && <TicketArchiveButton key={ticket.id} id={ticket.id} status={ticket.status} onArchived={onClose} />}
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => void submit()}>{ticket ? "Save" : "Create ticket"}</Button>
        </>
      }
    >
      <form className="space-y-4" onSubmit={(e) => (e.preventDefault(), void submit())}>
        {ticket && (
          <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <Chip>{LANE_TITLES[ticket.status as Lane] ?? ticket.status}</Chip>
            {ticket.branch && <span className="font-mono">{ticket.branch}</span>}
            {ticket.holdReason && <span>held: {ticket.holdReason}</span>}
          </div>
        )}
        <div className="space-y-1.5">
          <Label htmlFor="ticket-title">Title</Label>
          <Input id="ticket-title" autoFocus placeholder="What needs doing?" aria-invalid={!!err} {...form.register("title")} />
          {err && (
            <p role="alert" className="text-xs text-danger">
              {err}
            </p>
          )}
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="ticket-desc">Description</Label>
          <Textarea id="ticket-desc" rows={8} className="w-full" {...form.register("description")} />
        </div>
        {ticket && <TicketNotify ticketId={ticket.id} />}
        {ticket && <TicketComments ticketId={ticket.id} />}
        <AdvancedSection>
          <PickerField
            id="ticket-type"
            label="Type"
            options={TYPES}
            inheritLabel="No type"
            value={form.watch("ticketType")}
            onChange={(v) => form.setValue("ticketType", v)}
          />
          <PickerField
            id="ticket-epic"
            label="Epic"
            options={epicOptions}
            labels={epicNames}
            inheritLabel="No epic"
            value={form.watch("epicId")}
            onChange={(v) => form.setValue("epicId", v)}
          />
        </AdvancedSection>
      </form>
    </AppDialog>
  );
}
