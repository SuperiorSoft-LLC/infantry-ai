// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The renderer catalog (RUST-UI-5), this shell's UiRegistrar: a declared tab id (module.json `tabs[].id`) -> the view that
// draws it. WHICH tabs show, and in what order, comes from the daemon's plugin list (the slot registry): an enabled plugin's
// declared tab appears; a disabled plugin's disappears; a declared tab with no renderer here gets the generic plugin view.
// `builtin` entries are the shell's own views (session CLI, mail, health, plugin manager...): they show with no plugin.
// A declared tab that a web UI plugin draws (WEBUI-1, its module.json `ui.contributes`) shows the plugin's own view,
// icon and key hint; where each sits is the layout provider's call (default-layout.ts).
import { useMemo, type ComponentType, type ReactNode } from "react";
import {
  Activity,
  Bell,
  Box,
  Calendar,
  Cpu,
  FileText,
  Gauge,
  GitBranch,
  GitCompare,
  GitPullRequest,
  Globe,
  IdCard,
  Layers,
  Mail,
  MessageSquare,
  Play,
  Plug,
  Puzzle,
  Server,
  Settings,
  Sparkles,
  SquareTerminal,
  Star,
  User,
  UserCircle,
  ScrollText,
  type LucideIcon,
} from "lucide-react";
import { declLabel, type Contribution, type SlotRegistry } from "@infantry/client";
import { TabPage } from "@/components/tabs/tab-page";
import { EpicsTab, PodsTab, ProfilesTab } from "@/components/panels/board-panels";
import { McpPanel, ReviewPanel, ServicesTab, SitesPanel } from "@/components/panels/service-panels";
import { SettingsPage } from "@/components/panels/settings-page";
import { BoardTab } from "@/components/tabs/board-tab";
import { CalendarTab } from "@/components/tabs/calendar-tab";
import { RolesTab } from "@/components/roles/roles-tab";
import { NotesTab } from "@/components/knowledge/notes-tab";
import { SkillsTab } from "@/components/library/skills-tab";
import { ScriptsTab } from "@/components/scripts/scripts-tab";
import { PromptsTab } from "@/components/library/prompts-tab";
import { MailTab } from "@/components/mail/mail-tab";
import { ChangesPanel, GitTab } from "@/components/git/git-tab";
import { RunTab } from "@/components/run/run-tab";
import { ProcessesTab } from "@/components/run/processes-tab";
import { UsagePanel } from "@/components/usage/usage-panel";
import { AttentionPanel } from "@/components/usage/attention-panel";
import { HealthTab } from "@/components/health/health-tab";
import { PluginManager } from "@/components/plugins/plugin-manager";
import { arrangeBy } from "@infantry/ui-plugin-api/layout";
import { layoutProvider } from "./default-layout";
import { PluginFallback } from "./plugin-fallback";
import { useRegistry } from "./use-plugins";
import { PluginView, webTabIcon } from "./web-plugins";

export interface TabEntry {
  /** The view id the UI state stores (`cli`, `triggers`, ...). */
  id: string;
  label: string;
  /** A lucide icon, or the one a web UI plugin registered for its tab. */
  icon: ComponentType<{ className?: string }>;
  hint?: string;
  /** Null for the CLI view, which the session view draws itself. */
  render: () => ReactNode;
  /** The declared module.json tab id this view draws, when a plugin provides it. */
  declared?: string;
  /** Shown with no plugin declaring it. */
  builtin?: boolean;
  /** The plugin that contributed it (null = the shell). */
  plugin?: string | null;
}

type Def = Omit<TabEntry, "plugin">;

/** Centre tabs the shell draws itself; their order is the layout's (default-layout.ts). */
const CENTRE: Def[] = [
  { id: "cli", declared: "infantry.cli", builtin: true, label: "CLI", icon: SquareTerminal, hint: "^1", render: () => null },
  { id: "mail", builtin: true, label: "Mail", icon: Mail, render: () => <MailTab /> },
  { id: "pods", declared: "infantry.pods", label: "Pods", icon: Box, hint: "^2", render: () => <PodsTab /> },
  { id: "roles", declared: "infantry.roles", label: "Roles", icon: User, hint: "^5", render: () => <RolesTab /> },
  { id: "profiles", declared: "infantry.profiles", label: "Profiles", icon: IdCard, hint: "^9", render: () => <ProfilesTab /> },
  { id: "calendar", declared: "infantry.calendar", label: "Calendar", icon: Calendar, render: () => <CalendarTab /> },
  { id: "epics", declared: "infantry.epics", label: "Epics", icon: Layers, render: () => <EpicsTab /> },
  { id: "notes", declared: "infantry.notes", label: "Notes", icon: FileText, render: () => <NotesTab /> },
  { id: "skills", builtin: true, label: "Skills", icon: Sparkles, render: () => <SkillsTab /> },
  { id: "prompts", builtin: true, label: "Prompts", icon: MessageSquare, render: () => <PromptsTab /> },
  { id: "scripts", builtin: true, label: "Scripts", icon: ScrollText, render: () => <ScriptsTab /> },
  { id: "run", declared: "infantry.run", label: "Run", icon: Play, render: () => <RunTab /> },
  { id: "git", declared: "infantry.git", label: "Git", icon: GitBranch, render: () => <GitTab /> },
  { id: "processes", builtin: true, label: "Processes", icon: Cpu, render: () => <ProcessesTab /> },
  { id: "health", builtin: true, label: "Health", icon: Activity, render: () => <HealthTab /> },
  { id: "plugins", declared: "infantry.settings.plugins", builtin: true, label: "Plugins", icon: Puzzle, render: () => <PluginManager /> },
  { id: "services", declared: "infantry.account", builtin: true, label: "Services", icon: Server, render: () => <ServicesTab /> },
  { id: "sites", declared: "infantry.sites", label: "Sites", icon: Globe, render: () => <TabPage title="Sites" description="Hosting servers (read-only here)"><SitesPanel /></TabPage> },
  { id: "settings", declared: "infantry.settings", label: "Settings", icon: Settings, render: () => <SettingsPage /> },
];

/** Right-panel tabs. */
const RIGHT: Def[] = [
  { id: "attention", declared: "infantry.attention", builtin: true, label: "Attention", icon: Bell, hint: "^6", render: () => <AttentionPanel /> },
  { id: "usage", declared: "infantry.usage", builtin: true, label: "Usage", icon: Gauge, hint: "^7", render: () => <UsagePanel /> },
  { id: "prompts", declared: "infantry.prompts", builtin: true, label: "Prompts", icon: MessageSquare, render: () => <PromptsTab /> },
  { id: "changes", declared: "infantry.changes", label: "Changes", icon: GitCompare, hint: "^3", render: () => <ChangesPanel /> },
  { id: "mcp", declared: "infantry.mcp", label: "MCP", icon: Plug, render: () => <McpPanel /> },
  { id: "review", declared: "infantry.review", label: "Review", icon: GitPullRequest, hint: "^4", render: () => <ReviewPanel /> },
  { id: "skills", declared: "infantry.skills", builtin: true, label: "Skills", icon: Star, render: () => <SkillsTab /> },
];

/** Icons for declared tabs that have no renderer yet (the generic view draws them). */
const FALLBACK_ICONS: Record<string, LucideIcon> = {
  "infantry.account": UserCircle,
  "infantry.settings": Settings,
  "infantry.sites": Globe,
  "infantry.changes": GitCompare,
  "infantry.mcp": Plug,
  "infantry.review": GitCompare,
};

/** The Board is opened from the sidebar, not the tab bar; it is still a centre view. */
export const BOARD: TabEntry = { id: "board", label: "Board", icon: Layers, render: () => <BoardTab />, builtin: true, plugin: null };

/** A declared tab's key hint, as its declaration gives it. Where it sits is the layout's call (default-layout.ts), never the plugin's. */
function placed(c: Contribution): Pick<TabEntry, "hint"> {
  return { hint: typeof c.decl.hint === "string" && c.decl.hint ? c.decl.hint : undefined };
}

function generic(c: Contribution): TabEntry {
  return { id: c.decl.id, declared: c.decl.id, label: declLabel(c.decl), icon: FALLBACK_ICONS[c.decl.id] ?? Puzzle, plugin: c.plugin, ...placed(c), render: () => <PluginFallback contribution={c} /> };
}

/** Whether the declaring plugin's web UI half draws tab [c] (a `tab` or `panel` contribution). */
function drawnByWeb(reg: SlotRegistry, c: Contribution): boolean {
  return reg.web[c.plugin]?.contributes.some((w) => w.id === c.decl.id && (w.slot === "tab" || w.slot === "panel")) ?? false;
}

/** A tab or panel a web UI plugin draws: its view (a whole page in the centre, a card on the right) and its icon. */
function webTab(c: Contribution, container: "center" | "right"): TabEntry {
  const frame = container === "center" ? "page" : "card";
  return { id: c.decl.id, declared: c.decl.id, label: declLabel(c.decl), icon: webTabIcon(c.plugin, c.decl.id), plugin: c.plugin, ...placed(c), render: () => <PluginView plugin={c.plugin} id={c.decl.id} frame={frame} /> };
}

/** The layout provider's order for a container (its region): placed components first, the rest after in their given order. */
function arrange(container: "center" | "right", tabs: TabEntry[]): TabEntry[] {
  return arrangeBy(layoutProvider(), container, tabs, (t) => t.declared ?? t.id);
}

/**
 * The tabs one container shows. With no registry (the daemon list is not in yet, or the app runs offline) every catalog
 * entry shows. With one: the catalog entries a plugin declares or that are built in, and the declared tabs with no
 * renderer here (a web UI plugin's view, else the generic one), then arranged by the layout; one it does not place goes after the rest, in
 * registry order.
 */
export function tabsFor(container: "center" | "right", reg: SlotRegistry | null): TabEntry[] {
  const defs = container === "center" ? CENTRE : RIGHT;
  if (!reg) return arrange(container, defs.map((d) => ({ ...d, plugin: null })));
  const declared = reg.tabs.filter((t) => (t.decl.container ?? "center") === container);
  const byId = new Map(declared.map((t) => [t.decl.id, t]));
  const known = new Set(defs.map((d) => d.declared).filter(Boolean) as string[]);
  // A catalog renderer serves its tab in whichever container declares it.
  const other = container === "center" ? RIGHT : CENTRE;
  const otherKnown = new Set(other.map((d) => d.declared).filter(Boolean) as string[]);
  const shown = defs.filter((d) => d.builtin || (d.declared && byId.has(d.declared))).map((d) => ({ ...d, plugin: d.declared ? (byId.get(d.declared)?.plugin ?? null) : null }));
  const extra = declared.filter((t) => !known.has(t.decl.id) && !otherKnown.has(t.decl.id)).map((t) => (drawnByWeb(reg, t) ? webTab(t, container) : generic(t)));
  return arrange(container, [...shown, ...extra]);
}

/** Finds the entry for a stored view id across both containers (or the Board). */
export function entryFor(id: string, reg: SlotRegistry | null): TabEntry | undefined {
  if (id === BOARD.id) return BOARD;
  return tabsFor("center", reg).find((t) => t.id === id) ?? tabsFor("right", reg).find((t) => t.id === id);
}

/** The tabs of one container, recomputed only when the registry changes. */
export function useTabs(container: "center" | "right"): TabEntry[] {
  const reg = useRegistry();
  return useMemo(() => tabsFor(container, reg), [container, reg]);
}
