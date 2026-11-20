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
 */
export const SLOTS = ["tab", "panel", "settings.card", "plugin.config", "card.badge"] as const;
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
  /** Where a tab sits among the app's own (100, 200, ... in their order); none = after them. */
  order?: number;
  [k: string]: unknown;
}

/** The props each slot's view is drawn with. */
export interface SlotProps {
  tab: Record<string, never>;
  panel: Record<string, never>;
  "settings.card": Record<string, never>;
  "plugin.config": { plugin: string };
  "card.badge": { ticket: { id: string; title: string; status: string; [k: string]: unknown } };
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
export interface HostApi {
  /** The daemon link's state (`open`, `connecting`, `closed`...); re-renders when it changes. */
  useLinkState(): string;
}

/** What the app installs under HOST_GLOBAL. */
export interface UiHost {
  apiVersion: number;
  modules: Record<HostModule, unknown>;
  api: HostApi;
}

export type { FrameLink, Requester };
