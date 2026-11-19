// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The generated worker avatar: a pixel-art bot in the role colour, one look per role id (the Infantry_Mascot style),
// with live motion per state. Motion is CSS transform/opacity only and switches off under reduced motion.
import { memo } from "react";
import type { AgentStatus } from "@/mock/types";

/** A stable 32-bit hash, so a role keeps its look across restarts. */
export function hashOf(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619) >>> 0;
  return h;
}

/** The look a role id maps to: crest shape, ear shape, eye style. Pure, so it is testable. */
export function lookOf(roleId: string): { crest: number; ears: number; eyes: number } {
  const h = hashOf(roleId);
  return { crest: h % 4, ears: (h >> 3) % 3, eyes: (h >> 6) % 2 };
}

const SAFE = /^#[0-9a-fA-F]{6}$/;
const dark = (c: string, pct: number) => `color-mix(in oklab, ${c} ${pct}%, #0b0d12)`;

/** Which motion a state gets (live avatars only). */
export function motionOf(status: AgentStatus): "bob" | "shake" | "blink" | "float" | "none" {
  if (status === "working") return "bob";
  if (status === "needs-input") return "float";
  if (status === "error") return "shake";
  if (status === "done") return "none";
  return "blink";
}

export const PixelBot = memo(function PixelBot({
  roleId,
  color,
  status = "idle",
  live = true,
  className,
}: {
  roleId: string;
  color: string;
  status?: AgentStatus;
  live?: boolean;
  className?: string;
}) {
  const body = SAFE.test(color) ? color : "#6b7cff";
  const { crest, ears, eyes } = lookOf(roleId);
  const motion = live ? motionOf(status) : "none";
  const plate = dark(body, 22);
  const light = "#f4f7ff";
  const bodyClass = motion === "bob" ? "px-bob" : motion === "shake" ? "px-shake" : motion === "float" ? "px-float" : undefined;
  const blink = motion === "blink" || motion === "bob";
  const eyeY = status === "needs-input" ? 4 : 5;
  return (
    <svg viewBox="0 0 12 12" shapeRendering="crispEdges" aria-hidden className={className}>
      <g className={bodyClass}>
        {/* antenna / crest */}
        {crest === 0 && <rect x="5" y="0" width="2" height="3" fill={body} />}
        {crest === 1 && (
          <>
            <rect x="3" y="1" width="2" height="2" fill={body} />
            <rect x="7" y="1" width="2" height="2" fill={body} />
          </>
        )}
        {crest === 2 && <rect x="2" y="2" width="8" height="1" fill={dark(body, 70)} />}
        {crest === 3 && (
          <>
            <rect x="5" y="1" width="2" height="2" fill={body} />
            <rect x="5" y="0" width="2" height="1" fill={light} />
          </>
        )}
        {/* ears */}
        {ears === 0 && <rect x="0" y="5" width="12" height="4" fill={dark(body, 70)} />}
        {ears === 1 && (
          <>
            <rect x="0" y="4" width="1" height="5" fill={dark(body, 70)} />
            <rect x="11" y="4" width="1" height="5" fill={dark(body, 70)} />
          </>
        )}
        {/* head and face plate */}
        <rect x="1" y="3" width="10" height="8" fill={body} />
        <rect x="2" y="4" width="8" height="5" fill={plate} />
        {/* eyes */}
        {status === "error" ? (
          <>
            <path d="M3 5h1v1H3zM5 5h1v1H5zM4 6h1v1H4zM3 7h1v1H3zM5 7h1v1H5z" fill={light} />
            <path d="M7 5h1v1H7zM9 5h1v1H9zM8 6h1v1H8zM7 7h1v1H7zM9 7h1v1H9z" fill={light} />
          </>
        ) : status === "done" ? (
          <>
            <path d="M3 6h1v1H3zM4 5h1v1H4zM5 6h1v1H5z" fill={light} />
            <path d="M7 6h1v1H7zM8 5h1v1H8zM9 6h1v1H9z" fill={light} />
          </>
        ) : (
          <g className={blink ? "px-blink" : undefined}>
            <rect x="3" y={eyeY} width="2" height={eyes === 0 ? 2 : 3} fill={light} />
            <rect x="7" y={eyeY} width="2" height={eyes === 0 ? 2 : 3} fill={light} />
          </g>
        )}
        {/* mouth */}
        {status === "done" ? (
          <path d="M4 8h4v1H4z" fill={light} />
        ) : status === "error" ? (
          <path d="M5 8h2v1H5z" fill={light} opacity="0.7" />
        ) : (
          <path d="M5 8h2v1H5z" fill={light} opacity="0.55" />
        )}
        {/* feet */}
        <rect x="3" y="11" width="2" height="1" fill={dark(body, 70)} />
        <rect x="7" y="11" width="2" height="1" fill={dark(body, 70)} />
      </g>
    </svg>
  );
});
