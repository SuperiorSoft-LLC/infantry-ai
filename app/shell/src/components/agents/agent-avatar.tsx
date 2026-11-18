// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Round agent avatar: a role glyph on a hue gradient, with an optional status dot (AgentAvatar.kt).
import { cn } from "@infantry/ui";
import type { Agent } from "@/mock/types";
import { RoleIcon } from "./role-icon";
import { StatusDot } from "@infantry/ui";

const sizes = {
  xs: "size-6 [&_svg]:size-3",
  sm: "size-8 [&_svg]:size-4",
  md: "size-10 [&_svg]:size-5",
  lg: "size-12 [&_svg]:size-6",
} as const;

export function AgentAvatar({
  agent,
  size = "md",
  showStatus = true,
  ring,
  className,
}: {
  agent: Agent;
  size?: keyof typeof sizes;
  showStatus?: boolean;
  ring?: boolean;
  className?: string;
}) {
  return (
    <span className={cn("relative inline-flex shrink-0", className)}>
      <span
        className={cn(
          "grid place-items-center rounded-full text-white shadow-inner",
          sizes[size],
          ring && "ring-2 ring-foreground/80 ring-offset-2 ring-offset-background",
        )}
        style={{
          background: `radial-gradient(circle at 30% 25%, hsl(${agent.hue} 80% 62%), hsl(${agent.hue + 30} 55% 22%))`,
        }}
      >
        <RoleIcon roleId={agent.roleId} />
      </span>
      {showStatus && (
        <StatusDot
          status={agent.status}
          pulse={agent.status === "working"}
          className="absolute -right-0.5 -bottom-0.5 size-2.5 ring-2 ring-card"
        />
      )}
    </span>
  );
}
