// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The chat composer, ported from the Kotlin PromptBox (plugins/official/Infantry_Chat/src/ChatSurface.kt, CHAT-MINIMIZE-1..5,
// PROMPT-BORDER-1/2, CHAT-QUEUE-1, ATTACH-INLINE-1). Top to bottom: the session's prompt card, the queued messages, the
// attached images, the `/` command list, ONE status line, then one large rounded box with two tab-shaped groups hanging
// from its bottom edge: tools on the left (Attach, Switch agent, Resume, Stop background jobs, End session / Remove agent,
// Restart session; with no session: Attach, New agent), Stop and Send on the right. Box and groups share ONE outline: the
// neutral border at rest, the brand colour (2px) while focused or hovered. A narrow composer keeps as many 48px tools as
// fit and puts the rest behind "More actions". A hint line shows under the groups only when there is something to say.
//
// Keys: Enter sends (queues while the agent is busy, spawns with no session); Shift+Enter is a new line; Ctrl+Shift+A
// attaches; Ctrl+V with an image on the clipboard attaches it. With the `/` list open: Up/Down move, Tab or Enter pick,
// Esc closes it until the text changes. An attached image becomes its `[image: <path>]` reference at the caret, so the
// text IS the attachment list: deleting the reference releases the image.
import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ArrowLeftRight, CircleX, Gauge, MoreHorizontal, Paperclip, Play, RotateCw, SendHorizontal, Square, Trash2, UserPlus, X } from "lucide-react";
import { cn, Textarea, DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger, Tooltip, TooltipContent, TooltipTrigger } from "@infantry/ui";
import {
  chatKey,
  composerStatusLine,
  composerToolsThatFit,
  imageRefs,
  imageTag,
  fileTag,
  insertAt,
  matchSlash,
  setDraft,
  draftOf,
  slashCatalog,
  slashPrefix,
  splitComposerTools,
  uploadImage,
  COMPOSER_GROUP_GAP_PX,
  COMPOSER_GROUP_INSET_PX,
  type QueuedTurnView,
  type SlashCommand,
} from "@infantry/client";
import { useShallow } from "zustand/react/shallow";
import { daemonStore, useDaemon } from "@/daemon/daemon";
import { useAgentList, useDaemonHost, useSyncing } from "@/daemon/agents";
import { useUi } from "@/state/ui-state";
import { openSessionDialog } from "./session-actions";
import { readImageFile, Thumb } from "./chat-images";
import { useProjects } from "@/components/git/use-projects";
import { useChatDraft, useChatView } from "./use-chat-view";
import { PromptCards } from "./prompt-cards";
import { ComposerRound } from "./floating-chip";

const NO_QUEUE: QueuedTurnView[] = [];
const ENDED = new Set(["EXITED", "FAILED", "ORPHANED"]);
/** Kotlin COMPOSER_MIN_LINES / MAX_LINES / EXPANDED_LINES; 13px text on a 20px line, 12px padding top and bottom. */
const LINE = 20;
const rows = (n: number) => n * LINE + 24;
/** Kotlin COMPOSER_JOIN_RADIUS_DP / COMPOSER_TAB_RADIUS_DP. */
const JOIN = 6;
const IMAGE_MAX = 10 * 1024 * 1024;
/** The daemon's limit for a non-image attachment (Infantry_Chat images.rs MAX_FILE_BYTES). */
const FILE_MAX = 20 * 1024 * 1024;

interface Act {
  label: string;
  icon: ReactNode;
  shortcut?: string;
  enabled: boolean;
  tone?: "plain" | "danger" | "filled";
  run: () => void;
}

/**
 * One hanging group (Kotlin `composerTab`): open at the top, the box's fill, the box's outline continued down each side
 * and round the bottom (12px corners). It is pulled up over the box's bottom line, which its fill hides under the
 * opening; at each top corner a concave 6px fillet turns that line down into the group's side.
 */
function HangingGroup({ label, children }: { label: string; children: ReactNode }) {
  const fillet = (side: "left" | "right") => ({
    [side]: -JOIN,
    background: `radial-gradient(circle at ${side === "left" ? "0" : "100%"} 100%, transparent calc(${JOIN}px - var(--ring-w)), var(--ring-c) calc(${JOIN}px - var(--ring-w)), var(--ring-c) ${JOIN}px, var(--color-card) ${JOIN}px)`,
  });
  return (
    <div
      role="toolbar"
      aria-label={label}
      className="relative z-[1] flex min-w-0 items-center rounded-b-xl bg-card px-0.5"
      style={{ marginTop: "calc(-1 * var(--ring-w))", border: "var(--ring-w) solid var(--ring-c)", borderTop: 0, transition: "border-color 120ms" }}
    >
      <span aria-hidden className="pointer-events-none absolute top-0" style={{ width: JOIN, height: JOIN, ...fillet("left") }} />
      <span aria-hidden className="pointer-events-none absolute top-0" style={{ width: JOIN, height: JOIN, ...fillet("right") }} />
      {children}
    </div>
  );
}

/** "More actions": the tools that did not fit, as icon + label rows (dimmed when disabled). */
function ComposerMore({ rest }: { rest: Act[] }) {
  return (
    <DropdownMenu>
      <Tooltip>
        <DropdownMenuTrigger asChild>
          <TooltipTrigger asChild>
            <button type="button" aria-label="More actions" className="group/round grid size-12 shrink-0 cursor-pointer place-items-center outline-none">
              <span className="grid size-9 place-items-center rounded-full bg-accent text-foreground group-hover/round:bg-muted [&_svg]:size-[18px]">
                <MoreHorizontal />
              </span>
            </button>
          </TooltipTrigger>
        </DropdownMenuTrigger>
        <TooltipContent>More actions</TooltipContent>
      </Tooltip>
      <DropdownMenuContent align="start" side="top">
        {rest.map((a) => (
          <DropdownMenuItem key={a.label} disabled={!a.enabled} onSelect={a.run} className={cn("text-xs", a.tone && a.tone !== "plain" && "text-danger")}>
            {a.icon} {a.label}
            {a.shortcut && <span className="ml-auto pl-4 text-muted-foreground">{a.shortcut}</span>}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

/** `windowId` keys the remembered draft / expanded state (CHAT-MINIMIZE-1); minimize itself is ChatPanel's floater. */
export function Composer({ sessionId, busy, windowId = "main" }: { sessionId: string | null; busy: boolean; windowId?: string }) {
  const { expanded } = useChatView(windowId);
  // CHAT-DRAFT-1: the draft is this chat's in this window, never the window's (it must not follow into another chat).
  const chat = chatKey(sessionId);
  const text = useChatDraft(windowId, chat);
  const setText = (t: string) => setDraft(windowId, chat, t);
  const ui = useUi();
  const record = useDaemon((s) => (sessionId ? s.sessions[sessionId]?.record : undefined));
  const queued = record?.queued ?? NO_QUEUE;
  const online = useDaemon((s) => s.connection.state === "open");
  const agentCount = useAgentList().length;
  const syncing = useSyncing();
  const host = useDaemonHost();
  // RUST-MIGRATE-1: a session live in the other app is never run here too (the daemon refuses it as well).
  const heldBy = record?.heldBy ?? undefined;
  // CHAT-GONE-1: the daemon no longer has this session (removed, ended, unknown after a restart): nothing can be sent.
  const gone = useDaemon((s) => (sessionId ? s.gone[sessionId] : undefined));
  const goneText = gone?.text;
  const { send, spawn, interrupt, unqueue, forceQueued, sendFrame } = useDaemon(
    useShallow((s) => ({ send: s.send, spawn: s.spawn, interrupt: s.interrupt, unqueue: s.unqueue, forceQueued: s.forceQueued, sendFrame: s.sendFrame })),
  );
  const box = useRef<HTMLTextAreaElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  // ---- attachments: an image becomes its `[image: path]` reference at the caret (Kotlin ATTACH-INLINE-1) ----
  const [uploading, setUploading] = useState(0);
  const [attachError, setAttachError] = useState<string | null>(null);
  // An attach error is about the chat it happened in; it does not follow into another chat.
  useEffect(() => setAttachError(null), [chat]);
  const sessionProject = record?.projectId;
  const currentProject = useProjects((p) => p.currentId);
  const project = sessionProject || currentProject;
  const refs = useMemo(() => imageRefs(text).refs, [text]);
  const attach = async (files: File[]) => {
    if (!files.length) return;
    setAttachError(null);
    if (!project) return setAttachError("Pick a project first: attachments are stored in a project.");
    let caret = box.current?.selectionStart ?? text.length;
    const into = chat; // an upload lands in the chat it was started from, even if the user switched away meanwhile
    for (const f of files) {
      const name = f.name || "pasted file";
      // Images go as chat images (shown as thumbnails); any other file (text, logs, PDFs, code) is stored as a file and named by path.
      const image = /^image\/(png|jpeg|gif|webp)$/.test(f.type);
      const max = image ? IMAGE_MAX : FILE_MAX;
      if (f.size > max) {
        setAttachError(`${name}: the file is over the ${max / (1024 * 1024)} MB limit`);
        continue;
      }
      setUploading((n) => n + 1);
      try {
        const r = await uploadImage(daemonStore().getState(), project, await readImageFile(f), f.type, f.name, image ? "chat" : "file");
        if (r.ok) {
          const cur = draftOf(windowId, into);
          const at = insertAt(cur, caret, (image ? imageTag : fileTag)(r.path));
          setDraft(windowId, into, at.text);
          caret = at.caret;
        } else setAttachError(`${name}: ${r.text}`);
      } catch {
        setAttachError(`${name}: could not be read`);
      } finally {
        setUploading((n) => n - 1);
      }
    }
    box.current?.focus();
  };
  const pick = () => online && !heldBy && fileInput.current?.click();
  const removeRef = (path: string) => setText(text.replace(imageTag(path), "").replace(/\n{3,}/g, "\n\n"));

  // ---- the `/` command list ----
  const [catalog, setCatalog] = useState<SlashCommand[]>([]);
  const [slashAt, setSlashAt] = useState(0);
  const [dismissedFor, setDismissedFor] = useState<string | null>(null);
  useEffect(() => {
    setCatalog([]);
    if (!sessionId || !online) return;
    let live = true;
    void slashCatalog(daemonStore().getState(), sessionId).then((c) => live && setCatalog(c));
    return () => {
      live = false;
    };
  }, [sessionId, online]);
  const prefix = slashPrefix(text);
  const matches = useMemo(() => (prefix === null || dismissedFor === text ? [] : matchSlash(catalog, prefix)), [catalog, prefix, dismissedFor, text]);
  const at = Math.min(slashAt, Math.max(0, matches.length - 1));
  const complete = (c: SlashCommand) => (setText(`/${c.name}${c.argument || c.values?.length ? " " : ""}`), setSlashAt(0));

  // ---- send ----
  const canSend = online && !heldBy && !gone && uploading === 0 && text.trim().length > 0;
  // A refused message comes back into the box (before anything typed since), so the words are never lost.
  useEffect(() => {
    if (!sessionId || !goneText) return;
    const back = daemonStore().getState().takeGoneText(sessionId);
    if (!back) return;
    // The words go back into THAT chat's draft only; a "New agent" started from here starts empty.
    const cur = draftOf(windowId, sessionId);
    if (!cur.includes(back)) setDraft(windowId, sessionId, cur.trim() ? `${back}

${cur}` : back);
  }, [sessionId, goneText, windowId]);
  const submit = () => {
    if (!canSend) return;
    const full = text.trim();
    if (sessionId) send(sessionId, full);
    else spawn(full);
    setText("");
    setAttachError(null);
  };

  // ---- the tools group (Kotlin S:641-674) ----
  const ended = !!record && ENDED.has(String(record.state));
  const dormant = !!record?.dormant;
  const bgPids = (record?.bgJobs ?? []).map((j) => Number(j.pid)).filter((p) => p > 0);
  const tools: Act[] = [{ label: "Attach file", icon: <Paperclip />, shortcut: "Ctrl+Shift+A", enabled: online && !heldBy, run: pick }];
  if (sessionId && gone) {
    tools.push({ label: "New agent", icon: <UserPlus />, enabled: online, run: () => ui.set("dialog", "new-agent") });
  } else if (sessionId) {
    tools.push(
      { label: "Switch agent", icon: <ArrowLeftRight />, enabled: online, run: () => openSessionDialog("swap", sessionId) },
      { label: "Max context", icon: <Gauge />, enabled: online, run: () => openSessionDialog("limit", sessionId) },
      {
        label: dormant ? "Resume session" : "Resume",
        icon: <Play />,
        shortcut: "Ctrl+R",
        enabled: online && !heldBy && (ended || dormant),
        // A dormant session with an empty box attaches the CLI with no turn; typed text is sent as the next turn.
        run: () => (text.trim() ? submit() : void sendFrame({ type: "session_restart", data: JSON.stringify({ sessionId, force: "false" }), sessionId })),
      },
      {
        label: "Stop background jobs",
        icon: <CircleX />,
        tone: "danger",
        enabled: online && bgPids.length > 0,
        run: () => bgPids.forEach((pid) => sendFrame({ type: "processes_reap", data: JSON.stringify({ op: "reap", pid }), sessionId })),
      },
      ended
        ? { label: "Remove agent", icon: <Trash2 />, tone: "danger", enabled: online, run: () => openSessionDialog("delete", sessionId) }
        : { label: "End session", icon: <Trash2 />, tone: "filled", enabled: online, run: () => void sendFrame({ type: "kill", data: "", sessionId }) },
      { label: "Restart session", icon: <RotateCw />, enabled: online, run: () => openSessionDialog("restart", sessionId) },
    );
  } else {
    tools.push({ label: "New agent", icon: <UserPlus />, enabled: online, run: () => ui.set("dialog", "new-agent") });
  }
  const sendCount = sessionId ? 2 : 1;
  const groupsRow = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const el = groupsRow.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setWidth(e.contentRect.width + 2 * COMPOSER_GROUP_INSET_PX));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const { inline, overflow } = splitComposerTools(tools.length, width ? composerToolsThatFit(width, sendCount) : tools.length);

  // ---- one outline for box + groups (PROMPT-BORDER-1): neutral 1px at rest, brand 2px focused or hovered ----
  const [focused, setFocused] = useState(false);
  const [hovered, setHovered] = useState(false);
  const active = focused || hovered;
  const ring = { "--ring-w": active ? "2px" : "1px", "--ring-c": active ? "var(--color-primary)" : "var(--color-border)" } as React.CSSProperties;

  const placeholder = gone
    ? "This worker has finished and is gone: messages to it are not sent"
    : heldBy
    ? `Live in ${heldBy}: resume it there, or stop it there first`
    : !sessionId
      ? "Task for a NEW agent. Enter to spawn, Shift+Enter for a new line"
      : busy
        ? "Agent is busy. Enter to queue: sent as its next turn when this one ends"
        : `Message ${record?.worktreeBranch ?? sessionId.slice(0, 8)}. Enter to send`;
  const hint = !online ? "Not connected: commands are disabled." : heldBy ? `This session is live in ${heldBy}.` : null;
  const goneLine = gone
    ? gone.reason === "refused"
      ? "This worker has finished and is gone. Your message was not sent; it is back in the box."
      : "This worker has finished and is gone. Messages to it are not sent."
    : null;

  return (
    <div
      className="bg-background p-2.5"
      data-testid="composer"
      onDragOver={(e) => e.dataTransfer.types.includes("Files") && e.preventDefault()}
      onDrop={(e) => {
        if (!e.dataTransfer.files.length) return;
        e.preventDefault();
        void attach(Array.from(e.dataTransfer.files));
      }}
    >
      {sessionId && <PromptCards sessionId={sessionId} />}
      {sessionId && queued.length > 0 && (
        <ul aria-label="Queued messages" className="mb-1 font-mono text-[11px] text-muted-foreground">
          {queued.map((q, i) => (
            <li key={q.id} className="flex items-center">
              <span className={cn("min-w-0 flex-1 truncate", i > 0 && "pl-2")}>
                {i === 0 ? `Queued (${queued.length}): ` : ""}
                {q.text.split("\n")[0]}
              </span>
              <QueueAction label="Send now (into the running turn)" disabled={!online} onClick={() => forceQueued(sessionId, q.id)}>
                <SendHorizontal />
              </QueueAction>
              <QueueAction label="Remove from queue" disabled={!online} onClick={() => unqueue(sessionId, q.id)}>
                <X />
              </QueueAction>
            </li>
          ))}
        </ul>
      )}
      {(refs.length > 0 || uploading > 0 || attachError) && (
        <div className="mb-1.5 space-y-1">
          {(refs.length > 0 || uploading > 0) && (
            <div className="flex items-center gap-1.5 overflow-x-auto" aria-label="Attached images">
              {refs.map((r) => (
                <span key={r.path} className="flex shrink-0 items-center gap-1 rounded-md border bg-accent p-1 [&_button]:size-8">
                  <Thumb image={r} />
                  <span className="max-w-32 truncate text-[10px]">{r.path.split(/[\\/]/).pop()}</span>
                  <button type="button" aria-label="Remove the image" title="Remove the image" onClick={() => removeRef(r.path)} className="grid !size-5 place-items-center text-muted-foreground hover:text-foreground">
                    <X className="size-3" />
                  </button>
                </span>
              ))}
              {uploading > 0 && <span className="text-[10px] text-muted-foreground">Uploading…</span>}
            </div>
          )}
          {attachError && (
            <p role="alert" className="text-[11px] text-danger">
              {attachError}
            </p>
          )}
        </div>
      )}
      {matches.length > 0 && (
        <ul aria-label="Commands" role="listbox" className="mb-1.5 max-h-48 overflow-auto bg-accent">
          {matches.map((c, i) => (
            <li
              key={c.name}
              role="option"
              aria-selected={i === at}
              onMouseDown={(e) => (e.preventDefault(), complete(c))}
              className={cn("flex cursor-pointer items-center gap-2.5 px-2.5 py-1", i === at && "bg-border")}
            >
              <span className="shrink-0 font-mono text-xs">/{c.name}</span>
              {c.argument && <span className="shrink-0 text-[11px] text-muted-foreground">{c.argument}</span>}
              <span className="min-w-0 flex-1 truncate text-[11px] text-muted-foreground">{c.description}</span>
            </li>
          ))}
        </ul>
      )}
      {goneLine && (
        <p role="alert" data-testid="composer-gone" className="mb-1 pl-1.5 text-[11px] text-danger">
          {goneLine}
        </p>
      )}
      <div className="truncate pb-0.5 pl-1.5 text-[11px] text-muted-foreground" data-testid="composer-status">
        {syncing ? `Syncing with ${host}…` : composerStatusLine(null, agentCount, gone ? "gone" : record ? (busy ? "working" : String(record.state).toLowerCase()) : null)}
      </div>
      <div style={ring} onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}>
        <div className="relative rounded-2xl bg-card" style={{ border: "var(--ring-w) solid var(--ring-c)", transition: "border-color 120ms" }}>
          <input
            ref={fileInput}
            type="file"
            multiple
            hidden
            onChange={(e) => (void attach(Array.from(e.target.files ?? [])), (e.target.value = ""))}
          />
          <Textarea
            ref={box}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onFocus={() => setFocused(true)}
            onBlur={() => setFocused(false)}
            onPaste={(e) => {
              const files = Array.from(e.clipboardData.files);
              if (files.length) (e.preventDefault(), void attach(files));
            }}
            onKeyDown={(e) => {
              if (matches.length > 0) {
                if (e.key === "ArrowDown" || e.key === "ArrowUp") {
                  e.preventDefault();
                  setSlashAt((at + (e.key === "ArrowDown" ? 1 : matches.length - 1)) % matches.length);
                  return;
                }
                if (e.key === "Tab" || (e.key === "Enter" && !e.shiftKey && !e.ctrlKey && prefix !== matches[at].name)) {
                  e.preventDefault();
                  complete(matches[at]);
                  return;
                }
                if (e.key === "Escape") {
                  e.preventDefault();
                  setDismissedFor(text);
                  return;
                }
              }
              if (e.ctrlKey && e.shiftKey && (e.key === "A" || e.key === "a")) {
                e.preventDefault();
                pick();
                return;
              }
              if (e.key === "Enter" && !e.shiftKey && !e.ctrlKey && !e.nativeEvent.isComposing) {
                e.preventDefault();
                submit();
              }
            }}
            aria-label="Prompt"
            disabled={!!heldBy}
            placeholder={placeholder}
            style={{ minHeight: rows(expanded ? 14 : 3), maxHeight: rows(expanded ? 28 : 8), lineHeight: `${LINE}px` }}
            className="field-sizing-content resize-none overflow-y-auto rounded-2xl border-0 bg-transparent px-4 py-3 text-[13px] shadow-none placeholder:truncate focus-visible:ring-0 dark:bg-transparent"
          />
        </div>
        {/* The two groups hang from the box's bottom line, inset clear of its corners, tools left and Stop/Send right. */}
        <div ref={groupsRow} className="flex items-start justify-between" style={{ paddingInline: COMPOSER_GROUP_INSET_PX, columnGap: COMPOSER_GROUP_GAP_PX }}>
          <HangingGroup label="Composer tools">
            {tools.slice(0, inline).map((a) => (
              <ComposerRound key={a.label} label={a.label} shortcut={a.shortcut} disabled={!a.enabled} tone={a.tone} onClick={a.run}>
                {a.icon}
              </ComposerRound>
            ))}
            {overflow > 0 && <ComposerMore rest={tools.slice(inline)} />}
          </HangingGroup>
          <HangingGroup label="Send">
            {sessionId && !gone && (
              <ComposerRound label="Stop turn" tone="danger" disabled={!busy || !online} onClick={() => interrupt(sessionId)}>
                <Square className="fill-current" />
              </ComposerRound>
            )}
            <ComposerRound label={!sessionId ? "Spawn" : gone ? "Cannot send: this worker is gone" : busy ? "Queue" : "Send"} shortcut="Enter" tone="accent" disabled={!canSend} onClick={submit}>
              <SendHorizontal />
            </ComposerRound>
          </HangingGroup>
        </div>
      </div>
      {hint && <p className={cn("truncate pt-1 text-[11px]", !online ? "text-danger" : "text-muted-foreground")}>{hint}</p>}
    </div>
  );
}

/** A queued row's small action (Kotlin QueueAction): a 24px hit box on desktop, 48px on a phone; the glyph stays small. */
function QueueAction({ label, disabled, onClick, children }: { label: string; disabled: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          type="button"
          aria-label={label}
          disabled={disabled}
          onClick={onClick}
          className="grid size-6 shrink-0 cursor-pointer place-items-center text-foreground disabled:cursor-default disabled:text-muted-foreground/50 max-sm:size-12 [&_svg]:size-3.5"
        >
          {children}
        </button>
      </TooltipTrigger>
      <TooltipContent>{label}</TooltipContent>
    </Tooltip>
  );
}
