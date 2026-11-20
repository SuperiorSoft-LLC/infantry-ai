// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC

/** How long ago [at] (epoch ms) was, in one short unit: "now", "5m", "3h", "2d"; "" without a time. */
export function ago(at?: number | null): string {
  if (!at) return "";
  const s = Math.max(0, Math.round((Date.now() - at) / 1000));
  if (s < 60) return "now";
  if (s < 3600) return `${Math.round(s / 60)}m`;
  if (s < 86_400) return `${Math.round(s / 3600)}h`;
  return `${Math.round(s / 86_400)}d`;
}
