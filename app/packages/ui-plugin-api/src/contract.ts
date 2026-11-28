// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// WEBUI-1: the web UI plugin contract, UI API 1. A plugin's module.json `ui` half names its ES module and stylesheets and
// the contributions it makes (`ui.contributes`, each in one slot); the daemon serves those files to the app, which imports
// the module and calls its `activate` with a [UiPluginContext]. The app lends the plugin its own React and UI kit (the
// HOST_MODULES, read from the HOST_GLOBAL it installs before any plugin loads), so a plugin bundles neither.
//
// The constants live in host.json and ui-exports.json, which the build helper (build/plugin-config.mjs) reads too.
// This file has no side effects: the app imports it for the types and constants; a plugin imports `@infantry/ui-plugin-api`.
import type { ComponentType, ReactNode } from "react";
import type { FrameLink, Requester } from "../../client/src/stores/link";
import host from "./host.json";
import uiExports from "./ui-exports.json";

/** The UI API this package describes. The app refuses a plugin built for another one, saying which both are. */
export const UI_API_VERSION: number = host.apiVersion;

/** The global the app installs its [UiHost] under, before it imports any plugin. */
export const HOST_GLOBAL: string = host.global;

/**
 * The attribute the app puts on every element it draws a plugin in (and on the plugin's portal root, where its dialogs and
 * menus go), set to the plugin's module id. The build helper scopes the plugin's stylesheet to it.
 */
export const SCOPE_ATTRIBUTE: string = host.scopeAttribute;

/**
 * Where a contribution shows (module.json `ui.contributes[].slot`):
 * - `tab`: a centre tab (beside CLI, Mail, Board...); `panel`: a right-panel tab.
 * - `settings.card`: a card on the Settings page.
 * - `plugin.config`: a card in this plugin's own settings, under Plugins (#42).
 * - `card.badge`: a badge on every board ticket card (its props carry the ticket).
 * - `layout`: a whole-window layout (DESK-2). When the user picks it (the `layout` preference), the shell draws this view INSTEAD
 *   of its own window and hands it today's shell as `Shell`, so the layout may wrap, shrink or hide the orchestrator.
 *   A layout contribution may carry `"defaultLayout": true`: it is then the layout of a device that has never chosen one.
 * - `chat.card` (PLUGIN-AGENTS-1): draws one chat card type in every chat (desktop, browser, phone). The contribution names
 *   the type it draws, `"card": "<type>"` (the type the module declares in module.json `cards`); its view gets the card
 *   as [ChatCardProps]. While the plugin is not loaded, or its view fails, the chat draws the generic card instead.
 * - `chat.message.actions` (PARITY-AUDIT-1 #8): small buttons under one chat message (an agent's text, the owner's prompt, a
 *   mail), drawn in every chat. The view gets the message as [ChatMessageActionProps]; draw nothing for a message you skip.
 */
export const SLOTS = ["tab", "panel", "settings.card", "plugin.config", "card.badge", "layout", "chat.card", "chat.message.actions"] as const;
export type Slot = (typeof SLOTS)[number];

/** The modules the app lends: a plugin's imports of these are the app's own copy (one React, one UI kit). */
export type HostModule = "react" | "react/jsx-runtime" | "react-dom" | "@infantry/ui";
export const HOST_MODULES = host.modules as readonly HostModule[];

/** The `@infantry/ui` exports UI API 1 lends (test/contract.test.ts keeps this list equal to the kit's exports). */
export const UI_EXPORTS: readonly string[] = uiExports;

/** One `ui.contributes` entry as module.json declares it. */
export interface Contribution {
  id: string;
  slot: Slot | string;
  /** The tab's name (tab, panel); else the last part of the id. */
  label?: string;
  /** The key hint shown on a tab; `^N` also binds Ctrl+N. */
  hint?: string;
  [k: string]: unknown;
}

/** The props each slot's view is drawn with. */
export interface SlotProps {
  tab: Record<string, never>;
  panel: Record<string, never>;
  "settings.card": Record<string, never>;
  "plugin.config": { plugin: string };
  "card.badge": { ticket: { id: string; title: string; status: string; [k: string]: unknown } };
  layout: LayoutViewProps;
  "chat.card": ChatCardProps;
  "chat.message.actions": ChatMessageActionProps;
}

/** One chat message as a `chat.message.actions` view gets it. */
export interface ChatMessageActionProps {
  sessionId: string;
  /** `text` (the agent), `user` (the owner's prompt) or `comms` (a mail into the chat). */
  message: { id: string; kind: "text" | "user" | "comms"; text: string; from?: string };
}

/**
 * One chat card as a `chat.card` view gets it: the fenced block ```card:<type> [id=..] [op=..]``` with a body of one JSON
 * object. Later blocks with the same id are already folded in (`op` set merges keys, append concatenates, replace swaps).
 */
export interface ChatCardProps {
  card: { type: string; id: string; op: "set" | "append" | "replace"; body: Record<string, unknown> };
}

/**
 * ARMORY-3: the shell's own updater (the Tauri updater and the release feed), lent so an app page can update the shell.
 * `check` asks the feed; `download` fetches and verifies the update in the background and installs nothing; `relaunch`
 * installs it and restarts the app (call it only on a user's click).
 */
export interface ShellUpdater {
  check(): Promise<{ available: boolean; version: string | null; current: string }>;
  download(): Promise<string>;
  relaunch(): Promise<void>;
}

/** The three buttons of the real (OS-level) window's frame. */
export interface WindowFrame {
  minimize(): void;
  toggleMaximize(): void;
  close(): void;
}

/**
 * What a layout may hand the shell it draws (additive; both optional, and a layout that passes neither gets the shell as it
 * always was). `headerActions` sit at the right end of the shell's header, just left of the window controls (or at the far
 * right where there are none). `statusLeading` sits at the left of the shell's bottom status line, which then keeps its own
 * status text on the right. Both are plain nodes; the shell names no plugin.
 */
export interface ShellProps {
  className?: string;
  headerActions?: ReactNode;
  statusLeading?: ReactNode;
}

/** What a `layout` view is drawn with: today's whole app, and a way to draw another plugin's page contribution. */
export interface LayoutViewProps {
  /**
   * The shell as it always was (tabs, sidebar, panels), drawn outside the plugin's style scope so nothing inside it changes.
   * It renders a placeholder (give it room with `className`, e.g. `min-h-0 flex-1`) and the shell fills that placeholder.
   * Hide the placeholder (display:none on it or an ancestor) to hide the shell while it keeps running and keeps its state;
   * unmount it to close the shell.
   */
  Shell: ComponentType<ShellProps>;
  /**
   * Draws contribution [id] of plugin [plugin] as a page (an installed plugin's tab), with the app's loading and fault states.
   * [route] (ARMORY-2, "open app at route") is a page inside that contribution, e.g. `app/tower`: the view receives it as its
   * `route` render prop and shows that page. The shell attaches no meaning to it; the plugin that draws the view does.
   */
  View: ComponentType<{ plugin: string; id: string; route?: string }>;
}

/** What a plugin draws for one of its contributions. */
export interface View<P = Record<string, never>> {
  render(props: P): ReactNode;
  /** The tab's icon (tab, panel), e.g. a lucide-react icon the plugin bundles. */
  icon?: ComponentType<{ className?: string }>;
}

export interface UiPluginContext {
  /** The plugin's module id (`Vendor_Module`). */
  readonly id: string;
  /** The UI API the app provides. */
  readonly apiVersion: number;
  /**
   * The window's daemon link: send a frame, listen to every frame. The listeners a plugin adds are removed when it is
   * disabled, so a disabled plugin hears nothing.
   */
  readonly link: FrameLink;
  /** Draws contribution [id] (a `ui.contributes` entry); its slot says where. Call it from `activate`. */
  register<P = Record<string, never>>(id: string, view: View<P>): void;
  /** Runs when the plugin is disabled or replaced by a new build (also: return a function from `activate`). */
  onDispose(fn: () => void): void;
}

/** A plugin module's default export. */
export interface UiPluginModule {
  /** The UI API it was built for (UI_API_VERSION at build time). */
  apiVersion: number;
  activate(ctx: UiPluginContext): void | (() => void) | Promise<void | (() => void)>;
}

/**
 * The app state a plugin may read, besides its daemon link. The UI itself (the kit, TabPage, Field, RefusalBanner, `ago`)
 * is `@infantry/ui`, one of the HOST_MODULES.
 */
/** The window event that asks the shell to open the setup wizard for one runner (`detail: {runner}`). */
export const OPEN_RUNNER_SETUP_EVENT = "infantry:setup-runner";

export interface HostApi {
  /** The daemon link's state (`open`, `connecting`, `closed`...); re-renders when it changes. */
  useLinkState(): string;
  /**
   * A small per-device preference (a string or a boolean) kept in this window's storage; the component re-renders when it
   * changes, here or in another plugin. The key `layout` is the active layout contribution (`<Plugin>/<id>`, or `default`).
   */
  usePreference<T extends string | boolean>(key: string, fallback: T): readonly [T, (next: T) => void];
  /** The agents of every project that are busy right now, read from the app's own session store (DESK-3: the taskbar badge). */
  useAgentActivity(): AgentActivity;
  /** The app's pixel-bot avatar for the agent with session id [agentId] (renders nothing for an unknown id). */
  AgentAvatar: ComponentType<{ agentId: string; size?: "xs" | "sm" }>;
  /**
   * The real window's frame (DESK-5: a layout's Start menu minimizes or exits the whole window); null where the platform
   * draws it (a browser) or there is none (a phone). Read it when you draw, not once at start-up.
   */
  windowFrame(): WindowFrame | null;
  /** The shell's own updater, or null where it has none (a browser, a phone) (ARMORY-3). */
  shellUpdater(): ShellUpdater | null;
  /** BOS-8: the id of the BattalionOS app this window is, or null (standalone, browser). The OS owns updates and the desktop there. */
  osApp?(): string | null;
  /** RR-14: opens the app's setup wizard aimed at the runner [runner] (`local` = this machine): tools, sign-in, keys and models there. */
  openRunnerSetup(runner: string): void;
  /**
   * PLUGIN-AGENTS-1: opens [url] outside the app (the system browser on desktop, a new tab in a browser or on a phone);
   * `target="_blank"` does nothing inside the desktop webview. Optional: an older app lends none.
   */
  openExternal?(url: string): Promise<void>;
}

/** One busy agent, as a taskbar shows it. */
export interface ActiveAgent {
  id: string;
  name: string;
  /** `working`, or `needs-input` when it waits for the user. */
  status: "working" | "needs-input";
  statusLine: string;
}

/** How many agents are working, how many need the user, and the agents behind both counts (needs-input first). */
export interface AgentActivity {
  working: number;
  needsInput: number;
  agents: readonly ActiveAgent[];
}

/** What the app installs under HOST_GLOBAL. */
export interface UiHost {
  apiVersion: number;
  modules: Record<HostModule, unknown>;
  api: HostApi;
}

export type { FrameLink, Requester };
