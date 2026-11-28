// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// PARITY-AUDIT-1 #17: the composer's attachment limits (settings, Kotlin ChatPanelState's 10/20 MB), its plugin slot
// and when "Send now" is greyed (Kotlin forcesMidTurn).

/** The plugin slot for composer actions (Kotlin ChatSurface `chat.composer.actions`). Empty = nothing is drawn. */
export const COMPOSER_ACTIONS_SLOT = "chat.composer.actions";
export const IMAGE_MAX_MB_KEY = "limits.chat.imageMaxMb";
export const FILE_MAX_MB_KEY = "limits.chat.fileMaxMb";
export const IMAGE_MAX_MB = 10;
export const FILE_MAX_MB = 20;

/** The byte limit for one attachment: images and other files each have their setting; an invalid value = the default. */
export function attachLimitBytes(image: boolean, imageMb: number, fileMb: number): number {
  const ok = (n: number, d: number) => (Number.isInteger(n) && n >= 1 && n <= 1024 ? n : d);
  return (image ? ok(imageMb, IMAGE_MAX_MB) : ok(fileMb, FILE_MAX_MB)) * 1024 * 1024;
}

/**
 * Why "Send now" cannot deliver, or null when it can. Only a harness that reports it cannot take input mid-turn
 * (`midTurnInput: false`) greys it, and only while a turn runs; unknown (absent) = enabled, as before.
 */
export function sendNowBlocked(midTurnInput: boolean | null | undefined, busy: boolean): string | null {
  return midTurnInput === false && busy ? "This agent's CLI cannot take a message mid-turn; it is sent when the turn ends" : null;
}
