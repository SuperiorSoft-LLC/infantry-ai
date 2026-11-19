// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Worker avatar (AgentAvatar.kt): the role's picture when the daemon has one (override or bundled), else the generated
// pixel bot in the role colour, with live motion per state and an optional status dot.
import { cn, StatusDot } from "@infantry/ui";
import { daemonStore } from "@/daemon/daemon";
import { useRoles } from "@/components/knowledge/use-knowledge";
import type { Agent, AgentStatus } from "@/mock/types";
import { PixelBot } from "./pixel-bot";

const sizes = {
  xs: "size-6",
  sm: "size-8",
  md: "size-10",
  lg: "size-12",
  xl: "size-16",
} as const;
export type AvatarSize = keyof typeof sizes;

/** `ws://host:port/ws` -> `http://host:port`, where the daemon serves `/avatars/<id>`. */
export function httpBase(wsUrl: string): string {
  return wsUrl.replace(/^ws/, "http").replace(/\/ws\/?$/, "");
}

function avatarSrc(url: string, version?: string | null): string {
  if (/^https?:/.test(url)) return url;
  const base = httpBase(daemonStore().getState().connection.url);
  return `${base}${url}${version ? `?v=${encodeURIComponent(version)}` : ""}`;
}

/** The picture for a role: its image, else the generated pixel bot. Square, fills its box. */
export function RoleFace({ roleId, status = "idle", live = true, className }: { roleId: string; status?: AgentStatus; live?: boolean; className?: string }) {
  const role = useRoles((s) => s.roles[roleId]);
  const av = role?.avatar;
  const color = av?.color ?? role?.color ?? "#6b7cff";
  if (av?.url) {
    return <img src={avatarSrc(av.url, av.version)} alt="" draggable={false} className={cn("size-full object-cover [image-rendering:pixelated]", className)} />;
  }
  return <PixelBot roleId={roleId} color={color} status={status} live={live} className={cn("size-full", className)} />;
}

/** A role's avatar on its own (no agent): the dialog's role cards. */
export function RoleAvatar({ roleId, size = "md", className }: { roleId: string; size?: AvatarSize; className?: string }) {
  return (
    <span className={cn("inline-flex shrink-0 overflow-hidden rounded-md bg-muted/40 p-0.5", sizes[size], className)}>
      <RoleFace roleId={roleId} live={false} />
    </span>
  );
}

export function AgentAvatar({
  agent,
  size = "md",
  showStatus = true,
  ring,
  live = true,
  className,
}: {
  agent: Agent;
  size?: AvatarSize;
  showStatus?: boolean;
  ring?: boolean;
  /** Only the busy few move; a still list passes false. */
  live?: boolean;
  className?: string;
}) {
  return (
    <span className={cn("relative inline-flex shrink-0", className)}>
      <span
        className={cn(
          "inline-flex overflow-hidden rounded-lg bg-muted/40 p-0.5",
          sizes[size],
          ring && "ring-2 ring-foreground/80 ring-offset-2 ring-offset-background",
        )}
      >
        <RoleFace roleId={agent.roleId} status={agent.status} live={live} />
      </span>
      {showStatus && (
        <StatusDot
          status={agent.status}
          pulse={agent.status === "working" || agent.status === "needs-input"}
          className="absolute -right-0.5 -bottom-0.5 size-2.5 ring-2 ring-card"
        />
      )}
    </span>
  );
}
