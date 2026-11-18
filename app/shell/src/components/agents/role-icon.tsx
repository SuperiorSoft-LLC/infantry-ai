// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Maps a role icon name to its lucide glyph (AhqRoleIcons.kt equivalent).
import { Bot, ClipboardList, Compass, FlaskConical, Layers, Monitor, Server, Workflow, Cloud, type LucideIcon } from "lucide-react";
import type { RoleIconName } from "@/mock/types";
import { roles } from "@/mock/data";

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

export function RoleIcon({ roleId, className }: { roleId: string; className?: string }) {
  const role = roles.find((r) => r.id === roleId);
  const Icon = role ? icons[role.icon] : Bot;
  return <Icon className={className} aria-hidden />;
}
