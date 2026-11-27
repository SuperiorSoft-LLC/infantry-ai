// SPDX-License-Identifier: Apache-2.0
// GENERATED from crates/battalion-app-api/schemas/window.schema.json by kit/lib/windows/gen.ts. Do not edit:
// VENDORED from BattalionOS battalion-os @ 957c38f (WM-5), crates/battalion-desktop/ui/kit/lib. Do not edit here: change it in BattalionOS and copy again.
// change the schema (and battalion-app-api src/windows.rs), then run UPDATE_GEN=1 pnpm vitest run test/windows-gen.test.ts.

export type Rect = { x: number; y: number; w: number; h: number };
export type WindowKind = "inline" | "float" | "native";
export type SnapZone = "left" | "right" | "tl" | "tr" | "bl" | "br";
export type WindowState = "normal" | "minimized" | "maximized" | ({ snap: SnapZone });
export type WindowBadge = { count?: number; text?: string };
export type WindowSpec = { key: string; kind?: WindowKind; title?: string; icon?: string; route?: string; bounds?: Rect; state?: WindowState };
export type Opened = { wid: string; kind: WindowKind; state: WindowState; bounds: Rect; restored: boolean };
export type WindowInfo = { wid: string; key: string; kind: WindowKind; title: string; icon?: string; badge?: WindowBadge; attention?: boolean; state: WindowState; bounds: Rect; z: number; route?: string };
export type WindowPatch = { title?: string; icon?: string; badge?: WindowBadge | null; attention?: boolean; bounds?: Rect };
export type WinEvent = ({ change: "state"; wid: string; state: WindowState; bounds: Rect }) | ({ change: "closed"; wid: string }) | ({ change: "focus"; wid: string; z: number; state?: WindowState });
