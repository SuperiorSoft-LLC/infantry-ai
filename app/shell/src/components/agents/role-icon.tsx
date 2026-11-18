// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Maps a role icon name to its lucide glyph (AhqRoleIcons.kt equivalent).
import { Bot, ClipboardList, Compass, FlaskConical, Layers, Monitor, Server, Workflow, Cloud, type LucideIcon } from "lucide-react";
import type { RoleIconName } from "@/mock/types";
import { useRoles } from "@/components/knowledge/use-knowledge";

const icons: Record<RoleIconName, LucideIcon> = {
  architect: Compass,
  assistant: Bot,
  backend: Server,
  frontend: Monitor,
  fullstack: Layers,
  pm: ClipboardList,
  qa: FlaskConical,
  automation: Workflow,
  devops: Cloud,
};

/** The icon names a role may carry. */
export const ROLE_ICON_NAMES = Object.keys(icons) as RoleIconName[];

/** A role's glyph by its icon name (the daemon's `Role.icon`); unknown names draw the bot. */
export function RoleGlyph({ icon, className }: { icon: string; className?: string }) {
  const Icon = icons[icon as RoleIconName] ?? Bot;
  return <Icon className={className} aria-hidden />;
}

export function RoleIcon({ roleId, className }: { roleId: string; className?: string }) {
  const icon = useRoles((s) => s.roles[roleId]?.icon ?? "");
  return <RoleGlyph icon={icon} className={className} />;
}
