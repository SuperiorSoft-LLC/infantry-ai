// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// ChatPopout: a floating, draggable, resizable window with minimize and maximize around a ChatPanel. The Assistant box
// and "pop out this chat" both use it. Position, size and minimize / maximize are remembered per window id (like the
// Kotlin Assistant popup, which kept its width and expanded state across close and reopen).
import { useCallback, useEffect, useRef, useState, type PointerEvent as RPointerEvent, type ReactNode } from "react";
import { Maximize2, Minimize2, Minus, X, Square } from "lucide-react";
import { IconButton, cn } from "@infantry/ui";
import { ChatPanel, type ChatVariant } from "./chat-panel";
import { clampBox, dragBox, resizeBox, type Box, type Bounds } from "@infantry/client";

const KEY = "infantry.popout.";
// x < 0 means "right". The default starts below the tab bar and tab headers (Sign out, header buttons), so it never covers them.
const DEFAULT_Y = 200;
const DEFAULT_BOX: Box = { x: -1, y: DEFAULT_Y, w: 420, h: 560 };

interface Saved {
  box: Box;
  minimized: boolean;
  maximized: boolean;
}

function load(id: string): Saved {
  try {
    const raw = localStorage.getItem(KEY + id);
    if (raw) {
      const r = { box: DEFAULT_BOX, minimized: false, maximized: false, ...JSON.parse(raw) };
      // The old default (top right, never moved) was saved as is; it moves to the new default.
      if (r.box.x < 0 && r.box.y === 8) r.box = { ...r.box, y: DEFAULT_Y };
      return r;
    }
  } catch {
    /* storage unavailable or corrupt: defaults */
  }
  return { box: DEFAULT_BOX, minimized: false, maximized: false };
}

export interface ChatPopoutProps {
  /** Stable window id: geometry is remembered under it. */
  id: string;
  title: ReactNode;
  sessionId: string | null;
  variant?: ChatVariant;
  onClose: () => void;
  empty?: { title: string; hint: string; action?: ReactNode };
  /** The chat shows no composer until there is a session (see ChatPanel `sessionRequired`). */
  sessionRequired?: boolean;
  /** Extra header content (a status chip). */
  badge?: ReactNode;
  /** Stack order among popouts. */
  z?: number;
  onFocus?: () => void;
}

const HANDLE: Record<string, string> = {
  n: "inset-x-2 top-0 h-1.5 cursor-ns-resize",
  s: "inset-x-2 bottom-0 h-1.5 cursor-ns-resize",
  w: "inset-y-2 left-0 w-1.5 cursor-ew-resize",
  e: "inset-y-2 right-0 w-1.5 cursor-ew-resize",
  nw: "top-0 left-0 size-3 cursor-nwse-resize",
  ne: "top-0 right-0 size-3 cursor-nesw-resize",
  sw: "bottom-0 left-0 size-3 cursor-nesw-resize",
  se: "right-0 bottom-0 size-3 cursor-nwse-resize",
};

export function ChatPopout({ id, title, sessionId, variant = "popout", onClose, empty, sessionRequired, badge, z = 40, onFocus }: ChatPopoutProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const [saved, setSaved] = useState<Saved>(() => load(id));
  const [bounds, setBounds] = useState<Bounds>({ w: 1200, h: 800 });
  const { box, minimized, maximized } = saved;

  // The work area is the parent (below the title bar); measure it and keep the box inside when it shrinks.
  useEffect(() => {
    const el = hostRef.current?.parentElement;
    if (!el) return;
    const read = () => setBounds({ w: el.clientWidth, h: el.clientHeight });
    read();
    const ro = new ResizeObserver(read);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  useEffect(() => {
    try {
      localStorage.setItem(KEY + id, JSON.stringify(saved));
    } catch {
      /* the window still works for this run */
    }
  }, [id, saved]);

  // The untouched default (right edge, y = DEFAULT_Y) gets shorter on a short window instead of sliding up over the headers.
  const untouched = box.x < 0 && box.y === DEFAULT_Y;
  const shown = clampBox(untouched ? { ...box, h: Math.max(240, Math.min(box.h, bounds.h - DEFAULT_Y - 12)) } : box, bounds);
  const patch = useCallback((p: Partial<Saved>) => setSaved((s) => ({ ...s, ...p })), []);

  const gesture = useRef<{ kind: "move" | "resize"; edge: string; px: number; py: number; start: Box } | null>(null);
  const begin = (kind: "move" | "resize", edge: string) => (e: RPointerEvent) => {
    if (maximized || e.button !== 0) return;
    e.preventDefault();
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    gesture.current = { kind, edge, px: e.clientX, py: e.clientY, start: shown };
    onFocus?.();
  };
  const move = (e: RPointerEvent) => {
    const g = gesture.current;
    if (!g) return;
    const dx = e.clientX - g.px;
    const dy = e.clientY - g.py;
    patch({ box: g.kind === "move" ? dragBox(g.start, dx, dy, bounds) : resizeBox(g.start, g.edge, dx, dy, bounds) });
  };
  const end = () => {
    gesture.current = null;
  };

  const style = maximized ? { inset: 0 } : { left: shown.x, top: shown.y, width: shown.w, height: minimized ? undefined : shown.h };

  return (
    <div ref={hostRef} className="pointer-events-none absolute inset-0" style={{ zIndex: z }}>
      <section
        role="dialog"
        aria-label={typeof title === "string" ? title : "Chat"}
        data-popout={id}
        onPointerDown={onFocus}
        style={style}
        className={cn("pointer-events-auto absolute flex min-h-0 flex-col overflow-hidden border bg-background shadow-2xl", maximized ? "rounded-none" : "rounded-xl")}
      >
        <header
          onPointerDown={begin("move", "")}
          onPointerMove={move}
          onPointerUp={end}
          onPointerCancel={end}
          onDoubleClick={() => patch({ maximized: !maximized, minimized: false })}
          className={cn("flex h-9 shrink-0 items-center gap-1 border-b bg-card px-2 select-none", !maximized && "cursor-grab active:cursor-grabbing")}
        >
          <span className="min-w-0 flex-1 truncate text-sm font-semibold">{title}</span>
          {badge}
          <span onPointerDown={(e) => e.stopPropagation()} onDoubleClick={(e) => e.stopPropagation()} className="flex items-center gap-0.5">
            <IconButton label={minimized ? "Restore" : "Minimize"} className="size-7" onClick={() => patch({ minimized: !minimized, maximized: false })}>
              {minimized ? <Square /> : <Minus />}
            </IconButton>
            <IconButton label={maximized ? "Restore size" : "Maximize"} className="size-7" onClick={() => patch({ maximized: !maximized, minimized: false })}>
              {maximized ? <Minimize2 /> : <Maximize2 />}
            </IconButton>
            <IconButton label="Close" className="size-7" onClick={onClose}>
              <X />
            </IconButton>
          </span>
        </header>
        {!minimized && (
          <ChatPanel
            sessionId={sessionId}
            variant={variant}
            empty={empty}
            sessionRequired={sessionRequired}
            windowId={id}
          />
        )}
        {!minimized &&
          !maximized &&
          Object.keys(HANDLE).map((edge) => (
            <div
              key={edge}
              role="separator"
              aria-label={`Resize ${edge}`}
              onPointerDown={begin("resize", edge)}
              onPointerMove={move}
              onPointerUp={end}
              onPointerCancel={end}
              className={cn("absolute z-10", HANDLE[edge])}
            />
          ))}
      </section>
    </div>
  );
}
