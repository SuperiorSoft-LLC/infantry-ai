// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Prompt composer. Idle session: Enter sends the prompt. Busy session: red border, Enter queues the text as its next
// turn; the queued turns are listed above with remove / send-now. No session: Enter starts a new one.
// Stop interrupts the running turn; Swap agent and Restart session open the session dialogs. The others are still mock.
// Typing `/` lists the session provider's commands (`command_catalog`, fetched once per session) by prefix: Up/Down
// move, Tab or Enter completes, Esc closes. Images (paste, or the clip button) upload at once with `image_upload`
// (purpose chat, the session's project) and ride the prompt as `[image: <path>]` lines (Kotlin IMG-2).
import { useEffect, useMemo, useRef, useState } from "react";
import { ArrowLeftRight, ImagePlus, Play, CircleX, Trash2, RotateCw, Square, SendHorizontal, X, Zap, Maximize2, Minimize2, Search, History } from "lucide-react";
import { cn } from "@infantry/ui";
import { Textarea } from "@infantry/ui";
import { IconButton } from "@infantry/ui";
import { CHAT_SEARCH_EVENT, imageTag, matchSlash, slashCatalog, slashPrefix, uploadImage, type QueuedTurnView, type SlashCommand } from "@infantry/client";
import { useShallow } from "zustand/react/shallow";
import { daemonStore, useDaemon } from "@/daemon/daemon";
import { useUi } from "@/state/ui-state";
import { openSessionDialog } from "./session-actions";
import { readImageFile } from "./chat-images";
import { useProjects } from "@/components/git/use-projects";

const tools = [
  { label: "Swap agent", icon: ArrowLeftRight },
  { label: "Resume", icon: Play },
  { label: "Cancel turn", icon: CircleX },
  { label: "Delete session", icon: Trash2, danger: true },
  { label: "Restart session", icon: RotateCw },
];

const NO_QUEUE: QueuedTurnView[] = [];

export function Composer({ sessionId, busy }: { sessionId: string | null; busy: boolean }) {
  const [text, setText] = useState("");
  const ui = useUi();
  const actions: Record<string, (() => void) | undefined> = {
    Resume: () => ui.set("dialog", "resume"),
    "Cancel turn": sessionId && busy ? () => interrupt(sessionId) : undefined,
    "Swap agent": sessionId ? () => openSessionDialog("swap", sessionId) : undefined,
    "Restart session": sessionId ? () => openSessionDialog("restart", sessionId) : undefined,
    "Delete session": sessionId ? () => openSessionDialog("delete", sessionId) : undefined,
  };
  const queued = useDaemon((s) => (sessionId ? s.sessions[sessionId]?.record.queued : undefined)) ?? NO_QUEUE;
  const online = useDaemon((s) => s.connection.state === "open");
  // RUST-MIGRATE-1: a session live in the other app is never run here too (the daemon refuses it as well).
  const heldBy = useDaemon((s) => (sessionId ? s.sessions[sessionId]?.record.heldBy : undefined)) ?? undefined;
  // Actions are stable: picking them shallowly keeps the composer out of every streaming flush.
  const { send, spawn, interrupt, unqueue, forceQueued, requestHistory } = useDaemon(
    useShallow((s) => ({ send: s.send, spawn: s.spawn, interrupt: s.interrupt, unqueue: s.unqueue, forceQueued: s.forceQueued, requestHistory: s.requestHistory })),
  );

  const [images, setImages] = useState<{ path: string; name: string }[]>([]);
  const [uploading, setUploading] = useState(0);
  const [imageError, setImageError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const sessionProject = useDaemon((s) => (sessionId ? s.sessions[sessionId]?.record.projectId : undefined));
  const currentProject = useProjects((p) => p.currentId);
  const project = sessionProject || currentProject;
  const attach = async (files: File[]) => {
    const picked = files.filter((f) => f.type.startsWith("image/"));
    if (!picked.length) return;
    if (!project) return setImageError("Pick a project first: images are stored in a project.");
    setImageError(null);
    for (const f of picked) {
      setUploading((n) => n + 1);
      try {
        const r = await uploadImage(daemonStore().getState(), project, await readImageFile(f), f.type, f.name);
        if (r.ok) setImages((cur) => [...cur, { path: r.path, name: f.name || "pasted image" }]);
        else setImageError(`${f.name || "image"}: ${r.text}`);
      } catch {
        setImageError(`${f.name || "image"}: could not be read`);
      } finally {
        setUploading((n) => n - 1);
      }
    }
  };
  const [catalog, setCatalog] = useState<SlashCommand[]>([]);
  const [pick, setPick] = useState(0);
  const [hideSlash, setHideSlash] = useState(false);
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
  const matches = useMemo(() => (prefix === null || hideSlash ? [] : matchSlash(catalog, prefix)), [catalog, prefix, hideSlash]);
  const at = Math.min(pick, Math.max(0, matches.length - 1));
  const complete = (c: SlashCommand) => (setText(`/${c.name}${c.argument || c.values?.length ? " " : ""}`), setPick(0));

  const submit = () => {
    if ((!text.trim() && !images.length) || !online || heldBy || uploading) return;
    const full = [text.trim(), ...images.map((i) => imageTag(i.path))].filter(Boolean).join("\n");
    if (sessionId) send(sessionId, full);
    else spawn(full);
    setText("");
    setImages([]);
  };

  return (
    <div className="px-4 pb-3">
      {sessionId && queued.length > 0 && (
        <ul aria-label="Queued turns" className="mb-2 space-y-1">
          {queued.map((q) => (
            <li key={q.id} className="flex items-center gap-2 rounded-lg border border-dashed bg-card px-3 py-1 text-xs">
              <span className="shrink-0 text-muted-foreground">queued</span>
              <span className="min-w-0 flex-1 truncate">{q.text}</span>
              <IconButton label="Send now" className="size-6" onClick={() => forceQueued(sessionId, q.id)}>
                <Zap />
              </IconButton>
              <IconButton label="Remove from queue" className="size-6" onClick={() => unqueue(sessionId, q.id)}>
                <X />
              </IconButton>
            </li>
          ))}
        </ul>
      )}
      {matches.length > 0 && (
        <ul aria-label="Commands" role="listbox" className="mb-2 max-h-48 space-y-1 overflow-auto">
          {matches.map((c, i) => (
            <li
              key={c.name}
              role="option"
              aria-selected={i === at}
              onMouseDown={(e) => (e.preventDefault(), complete(c))}
              className={cn("flex cursor-pointer items-center gap-2 rounded-lg border bg-card px-3 py-1 text-xs", i === at && "border-ring")}
            >
              <span className="shrink-0 font-mono">/{c.name}</span>
              {c.argument && <span className="shrink-0 text-muted-foreground">{c.argument}</span>}
              <span className="min-w-0 flex-1 truncate text-muted-foreground">{c.description}</span>
            </li>
          ))}
        </ul>
      )}
      {(images.length > 0 || uploading > 0 || imageError) && (
        <ul aria-label="Attached images" className="mb-2 flex flex-wrap gap-1">
          {images.map((im, i) => (
            <li key={im.path} className="flex items-center gap-1 rounded-lg border bg-card px-2 py-0.5 text-xs" title={im.path}>
              <ImagePlus className="size-3 text-muted-foreground" />
              <span className="max-w-40 truncate">{im.name}</span>
              <IconButton label={`Remove ${im.name}`} className="size-5" onClick={() => setImages((cur) => cur.filter((_, j) => j !== i))}>
                <X />
              </IconButton>
            </li>
          ))}
          {uploading > 0 && <li className="px-2 py-0.5 text-xs text-muted-foreground">uploading {uploading}…</li>}
          {imageError && (
            <li role="alert" className="px-2 py-0.5 text-xs text-danger">
              {imageError}
            </li>
          )}
        </ul>
      )}
      <div className={cn("relative rounded-xl border-2 bg-card transition-colors", busy ? "border-danger" : "border-border focus-within:border-ring")}>
        {sessionId && (
          <div role="toolbar" aria-label="Chat view" className="absolute right-3 bottom-full -mb-0.5 flex items-center gap-1 rounded-t-lg border border-b-0 border-danger/60 bg-card p-1">
            <IconButton label="Maximize chat" className="rounded-full" disabled={ui.chatMax} onClick={() => ui.set("chatMax", true)}>
              <Maximize2 />
            </IconButton>
            <IconButton label="Minimize chat" className="rounded-full" disabled={!ui.chatMax} onClick={() => ui.set("chatMax", false)}>
              <Minimize2 />
            </IconButton>
            <IconButton label="Search chat" className="rounded-full" onClick={() => window.dispatchEvent(new Event(CHAT_SEARCH_EVENT))}>
              <Search />
            </IconButton>
            <IconButton label="Load history" className="rounded-full" onClick={() => requestHistory(sessionId)}>
              <History />
            </IconButton>
          </div>
        )}
        <Textarea
          value={text}
          onChange={(e) => (setText(e.target.value), setHideSlash(false))}
          onPaste={(e) => {
            const files = Array.from(e.clipboardData.files).filter((f) => f.type.startsWith("image/"));
            if (files.length) (e.preventDefault(), void attach(files));
          }}
          onKeyDown={(e) => {
            if (matches.length > 0) {
              if (e.key === "ArrowDown" || e.key === "ArrowUp") {
                e.preventDefault();
                setPick((at + (e.key === "ArrowDown" ? 1 : matches.length - 1)) % matches.length);
                return;
              }
              if (e.key === "Tab" || (e.key === "Enter" && !e.shiftKey && prefix !== matches[at].name)) {
                e.preventDefault();
                complete(matches[at]);
                return;
              }
              if (e.key === "Escape") {
                e.preventDefault();
                setHideSlash(true);
                return;
              }
            }
            if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              submit();
            }
          }}
          aria-label="Prompt"
          disabled={!!heldBy}
          placeholder={
            heldBy
              ? `Live in ${heldBy}: resume it there, or stop it there first`
              : !sessionId
              ? "Start a new session..."
              : busy
                ? "Agent is busy. Enter to queue: sent as its next turn when this one ends"
                : "Message the agent..."
          }
          className="min-h-20 resize-none border-0 bg-transparent px-4 py-3 text-[15px] shadow-none focus-visible:ring-0 dark:bg-transparent"
        />
        <div className="flex items-center justify-between gap-2 px-2 pb-2">
          <div role="toolbar" aria-label="Session actions" className="flex items-center gap-1 rounded-lg border border-danger/60 p-1">
            {tools.map(({ label, icon: Icon, danger }) => (
              <IconButton
                key={label}
                label={label}
                className={cn("rounded-full", danger && "bg-danger text-white hover:bg-danger/85 hover:text-white")}
                onClick={actions[label]}
              >
                <Icon />
              </IconButton>
            ))}
          </div>
          <div className="flex items-center gap-1 rounded-lg border border-danger/60 p-1">
            <input
              ref={fileInput}
              type="file"
              accept="image/png,image/jpeg,image/gif,image/webp"
              multiple
              hidden
              onChange={(e) => (void attach(Array.from(e.target.files ?? [])), (e.target.value = ""))}
            />
            <IconButton label="Attach image" disabled={!online || !!heldBy} onClick={() => fileInput.current?.click()}>
              <ImagePlus />
            </IconButton>
            <IconButton label="Stop" className="text-danger hover:text-danger" disabled={!busy || !sessionId || !online} onClick={() => sessionId && interrupt(sessionId)}>
              <Square className="fill-current" />
            </IconButton>
            <IconButton label={busy ? "Queue message" : "Send"} className="text-danger hover:text-danger" disabled={(!text.trim() && !images.length) || !online || !!heldBy || uploading > 0} onClick={submit}>
              <SendHorizontal />
            </IconButton>
          </div>
        </div>
      </div>
    </div>
  );
}
