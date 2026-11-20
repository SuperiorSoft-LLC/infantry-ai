// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The ONE owner of view state. Components read it through useUi(); nothing duplicates it.
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { layoutFor, type Layout } from "@infantry/client";

/** A right-panel view id; which ones exist comes from the plugin registry (plugins/catalog.tsx). */
export type RightTab = string;
export type DialogId = null | "settings" | "new-agent" | "new-ticket" | "resume" | "setup";
export type Theme = "dark" | "light";
/** What the centre column shows: a tab id from the plugin registry (plugins/catalog.tsx), or `board`. */
export type CentreView = string;

interface UiState {
  selectedAgentId: string;
  sessionTab: CentreView;
  rightTab: RightTab;
  roleFilter: string;
  search: string;
  theme: Theme;
  leftOpen: boolean;
  rightOpen: boolean;
  dialog: DialogId;
  /** Chat maximized: the header and tab bar step aside so the transcript and composer fill the column. */
  chatMax: boolean;
  /** The Assistant box (floating chat) is open. Its position and size are remembered by the window itself. */
  assistantOpen: boolean;
  /** Sessions popped out into their own floating chat window. */
  popouts: string[];
}

interface UiActions {
  set: <K extends keyof UiState>(key: K, value: UiState[K]) => void;
  toggle: (key: "leftOpen" | "rightOpen") => void;
  toggleTheme: () => void;
  popOut: (sessionId: string) => void;
  closePopout: (sessionId: string) => void;
}

const UiContext = createContext<(UiState & UiActions) | null>(null);

function initialTheme(): Theme {
  try {
    return localStorage.getItem("infantry.theme") === "light" ? "light" : "dark";
  } catch {
    return "dark";
  }
}

export function UiProvider({ children }: { children: ReactNode }) {
  const narrow = typeof window !== "undefined" && window.innerWidth < 1100;
  const [state, setState] = useState<UiState>({
    selectedAgentId: "",
    sessionTab: "cli",
    rightTab: "usage",
    roleFilter: "all",
    search: "",
    theme: initialTheme(),
    leftOpen: !narrow,
    rightOpen: !narrow,
    dialog: null,
    chatMax: false,
    assistantOpen: false,
    popouts: [],
  });

  useEffect(() => {
    document.documentElement.classList.toggle("dark", state.theme === "dark");
    try {
      localStorage.setItem("infantry.theme", state.theme);
    } catch {
      /* storage unavailable: theme still applies for this run */
    }
  }, [state.theme]);

  const set = useCallback<UiActions["set"]>((key, value) => setState((s) => ({ ...s, [key]: value })), []);
  const toggle = useCallback<UiActions["toggle"]>((key) => setState((s) => ({ ...s, [key]: !s[key] })), []);
  const toggleTheme = useCallback(() => setState((s) => ({ ...s, theme: s.theme === "dark" ? "light" : "dark" })), []);

  const popOut = useCallback((id: string) => setState((s) => (s.popouts.includes(id) ? s : { ...s, popouts: [...s.popouts, id] })), []);
  const closePopout = useCallback((id: string) => setState((s) => (s.popouts.includes(id) ? { ...s, popouts: s.popouts.filter((x) => x !== id) } : s)), []);

  const value = useMemo(() => ({ ...state, set, toggle, toggleTheme, popOut, closePopout }), [state, set, toggle, toggleTheme, popOut, closePopout]);
  return <UiContext.Provider value={value}>{children}</UiContext.Provider>;
}

export function useUi() {
  const ctx = useContext(UiContext);
  if (!ctx) throw new Error("useUi must be used inside <UiProvider>");
  return ctx;
}

/** True when the viewport is at or below `px` wide. */
/** The responsive layout for the window's width (RUST-MOBILE-1, `layoutFor`): re-read on resize and rotation. */
export function useLayout(): Layout {
  const read = () => (typeof window === "undefined" ? layoutFor(1600) : layoutFor(window.innerWidth, window.matchMedia("(pointer: coarse)").matches));
  const [l, setL] = useState(read);
  useEffect(() => {
    const on = () => setL((prev) => {
      const next = read();
      return prev.kind === next.kind && prev.leftInline === next.leftInline && prev.rightInline === next.rightInline && prev.tapTarget === next.tapTarget ? prev : next;
    });
    window.addEventListener("resize", on);
    return () => window.removeEventListener("resize", on);
  }, []);
  return l;
}

export function useNarrow(px: number) {
  const query = `(max-width: ${px}px)`;
  const [match, setMatch] = useState(() => typeof window !== "undefined" && window.matchMedia(query).matches);
  useEffect(() => {
    const mq = window.matchMedia(query);
    const on = () => setMatch(mq.matches);
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, [query]);
  return match;
}
