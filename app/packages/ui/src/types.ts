// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// Small value types the kit's blocks render. App data maps onto these.
export type Tone = "neutral" | "red" | "green" | "blue" | "amber";
export type StatusKind = "idle" | "working" | "waiting" | "needs-input" | "review" | "done" | "error";
export interface NamedRow {
  id: string;
  title: string;
  subtitle: string;
  meta?: string;
  tone?: Tone;
  badge?: string;
}
