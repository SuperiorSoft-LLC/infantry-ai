// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Session actions (RUST-SESS-2): interrupt (the store's existing `interrupt`), restart (`session_restart`; a mid-turn
// refusal offers a forced retry), switch provider/model (`session_swap`, providers from the launcher's harness list)
// and remove. Session stats live in the one Stats modal (top-right button). The composer toolbar opens these dialogs; every dialog is
// the one modal.
import { useEffect, useState } from "react";
import { createStore } from "zustand/vanilla";
import { useStore } from "zustand";
import { chatKey, createSessionActions, dropChatDraft, formatContextLimit, isMidTurnRefusal, parseContextLimit, type SessionActions } from "@infantry/client";
import { AppDialog, Button } from "@infantry/ui";
import { daemonStore, useDaemon } from "@/daemon/daemon";
import { useLauncher } from "@/components/dialogs/new-agent-dialog";
import { ContextLimitField } from "@/components/dialogs/context-limit-field";
import { ExecutorPicker, type ExecutorValue } from "@/components/dialogs/executor-picker";

type Modal = "restart" | "swap" | "limit" | "delete";

const modal = createStore<{ open: Modal | null; sessionId: string | null }>(() => ({ open: null, sessionId: null }));

/** Opens one of the session dialogs for [sessionId] (the composer toolbar uses this too). */
export function openSessionDialog(kind: Modal, sessionId: string): void {
  modal.setState({ open: kind, sessionId });
}

let actions: SessionActions | null = null;
export function sessionActions(): SessionActions {
  return (actions ??= createSessionActions(daemonStore().getState()));
}

/** Rendered once in the session view: whichever session dialog is open. */
export function SessionDialogs() {
  const { open, sessionId } = useStore(modal);
  const close = (): void => modal.setState({ open: null });
  if (!open || !sessionId) return null;
  if (open === "restart") return <RestartDialog sessionId={sessionId} onClose={close} />;
  if (open === "swap") return <SwapDialog sessionId={sessionId} onClose={close} />;
  if (open === "limit") return <ContextLimitDialog sessionId={sessionId} onClose={close} />;
  return <DeleteDialog sessionId={sessionId} onClose={close} />;
}

function Refusal({ text }: { text: string | null }) {
  return text ? (
    <p role="alert" className="rounded-md border border-danger/60 bg-danger/10 px-3 py-2 text-xs text-danger">
      {text}
    </p>
  ) : null;
}

/** Remove a session (`session_delete`): "This can't be undone", Cancel first. */
function DeleteDialog({ sessionId, onClose }: { sessionId: string; onClose: () => void }) {
  const name = useDaemon((s) => s.sessions[sessionId]?.record.role ?? null);
  const remove = (): void => {
    daemonStore().getState().sendFrame({ type: "session_delete", data: sessionId, sessionId });
    dropChatDraft(chatKey(sessionId)); // CHAT-DRAFT-1: a chat removed for good takes its unsent text with it
    onClose();
  };
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && onClose()}
      title={`Remove ${name ?? "this session"}?`}
      description="The session and its transcript are removed. This can't be undone."
      footer={
        <>
          <Button variant="ghost" autoFocus onClick={onClose}>
            Cancel
          </Button>
          <Button variant="destructive" onClick={remove}>
            Remove
          </Button>
        </>
      }
    >
      <p className="text-sm text-muted-foreground">A running turn is stopped first. Git branches and worktrees are not touched.</p>
    </AppDialog>
  );
}

function RestartDialog({ sessionId, onClose }: { sessionId: string; onClose: () => void }) {
  const [busy, setBusy] = useState(false);
  const [why, setWhy] = useState<string | null>(null);
  const midTurn = !!why && isMidTurnRefusal(why);
  const go = (force: boolean): void => {
    setBusy(true);
    setWhy(null);
    sessionActions()
      .restart(sessionId, force)
      .then(onClose, (e) => setWhy(String(e)))
      .finally(() => setBusy(false));
  };
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Restart session"
      description="Stops the agent process and starts it again on the same conversation."
      footer={
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={onClose}>
            Cancel
          </Button>
          {midTurn ? (
            <Button variant="destructive" size="sm" disabled={busy} onClick={() => go(true)}>
              Restart anyway
            </Button>
          ) : (
            <Button size="sm" disabled={busy} onClick={() => go(false)}>
              Restart
            </Button>
          )}
        </div>
      }
    >
      <div className="space-y-2 text-[13px]">
        <p>
          Restart <span className="font-mono text-xs">{sessionId.slice(0, 8)}</span>?
        </p>
        <Refusal text={why} />
        {midTurn && <p className="text-xs text-muted-foreground">The session is mid-turn. Restarting anyway ends the running turn.</p>}
      </div>
    </AppDialog>
  );
}

/** Modify session: show the effective max context and its source, change it, and say when it takes effect. */
function ContextLimitDialog({ sessionId, onClose }: { sessionId: string; onClose: () => void }) {
  const record = useDaemon((s) => s.sessions[sessionId]?.record);
  const [text, setText] = useState(formatContextLimit(record?.contextLimit));
  const [busy, setBusy] = useState(false);
  const [why, setWhy] = useState<string | null>(null);
  const [effect, setEffect] = useState<string | null>(null);
  const parsed = parseContextLimit(text);
  const bad = text.trim() !== "" && parsed == null;
  const now = record?.contextLimit == null ? "not set" : formatContextLimit(record.contextLimit);
  const submit = (): void => {
    setBusy(true);
    setWhy(null);
    sessionActions()
      .setContextLimit(sessionId, parsed)
      .then((r) => setEffect(r.effect), (e) => setWhy(String(e)))
      .finally(() => setBusy(false));
  };
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Max context"
      description={record ? `Now: ${now}${record.contextLimitSource ? ` (${record.contextLimitSource})` : ""} \u00b7 ${record.contextLimitSupport ?? "unknown support"}` : undefined}
      footer={
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={onClose}>
            {effect ? "Close" : "Cancel"}
          </Button>
          <Button size="sm" disabled={busy || bad} onClick={submit}>
            Apply
          </Button>
        </div>
      }
    >
      <div className="space-y-3">
        <ContextLimitField
          idPrefix="modify"
          value={text}
          onChange={(v) => {
            setText(v);
            setEffect(null);
          }}
          hint="blank = the settings default"
        />
        {record?.contextLimitHow && <p className="text-xs text-muted-foreground">{record.contextLimitHow}</p>}
        {effect && (
          <p role="status" className="text-xs">
            {effect}
          </p>
        )}
        <Refusal text={why} />
      </div>
    </AppDialog>
  );
}

function SwapDialog({ sessionId, onClose }: { sessionId: string; onClose: () => void }) {
  const { harnesses, catalog } = useLauncher();
  const record = useDaemon((s) => s.sessions[sessionId]?.record);
  const [pick, setPick] = useState<ExecutorValue>({ harness: record?.provider ?? null, group: null, model: null, effort: null });
  const [busy, setBusy] = useState(false);
  const [why, setWhy] = useState<string | null>(null);

  useEffect(() => {
    if (!pick.harness && harnesses.length) setPick((p) => ({ ...p, harness: harnesses.find((h) => h.default)?.id ?? harnesses[0].id }));
  }, [harnesses]); // eslint-disable-line react-hooks/exhaustive-deps

  const submit = (): void => {
    setBusy(true);
    setWhy(null);
    sessionActions()
      .swap({ sessionId, provider: pick.harness ?? "", model: pick.model ?? undefined, effort: pick.effort ?? undefined })
      .then(onClose, (e) => setWhy(String(e)))
      .finally(() => setBusy(false));
  };
  return (
    <AppDialog
      open
      onOpenChange={(o) => !o && onClose()}
      title="Switch provider or model"
      description={record ? `Now: ${record.provider}${record.model ? ` · ${record.model}` : ""}` : undefined}
      footer={
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={onClose}>
            Cancel
          </Button>
          <Button size="sm" disabled={busy || !pick.harness} onClick={submit}>
            Switch
          </Button>
        </div>
      }
    >
      <div className="space-y-3">
        <ExecutorPicker idPrefix="swap" value={pick} harnesses={harnesses} catalog={catalog} onChange={setPick} />
        <Refusal text={why} />
      </div>
    </AppDialog>
  );
}

