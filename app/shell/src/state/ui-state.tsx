// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The ONE owner of view state. Components read it through useUi(); nothing duplicates it.
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";

/** A right-panel view id; which ones exist comes from the plugin registry (plugins/catalog.tsx). */
export type RightTab = string;
export type DialogId = null | "settings" | "new-agent" | "new-ticket" | "resume";
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
}

interface UiActions {
  set: <K extends keyof UiState>(key: K, value: UiState[K]) => void;
  toggle: (key: "leftOpen" | "rightOpen") => void;
  toggleTheme: () => void;
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

  const value = useMemo(() => ({ ...state, set, toggle, toggleTheme }), [state, set, toggle, toggleTheme]);
  return <UiContext.Provider value={value}>{children}</UiContext.Provider>;
}

export function useUi() {
  const ctx = useContext(UiContext);
  if (!ctx) throw new Error("useUi must be used inside <UiProvider>");
  return ctx;
}

/** True when the viewport is at or below `px` wide. */
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
