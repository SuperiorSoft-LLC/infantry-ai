// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// ANDROID-PINCH-1: the app page never pinch-zooms; the in-app UI size replaces it.
import { describe, expect, it } from "vitest";
import indexHtml from "../index.html?raw";
import mainActivity from "../../src-tauri/gen/android/app/src/main/java/com/superiorsoft/infantry/MainActivity.kt?raw";

describe("pinch lock", () => {
  it("index.html viewport locks the page scale", () => {
    const meta = indexHtml.match(/<meta name="viewport" content="([^"]+)"/)?.[1] ?? "";
    for (const part of ["width=device-width", "initial-scale=1", "maximum-scale=1", "user-scalable=no"]) expect(meta).toContain(part);
  });
  it("the Android WebView turns zoom off and pins textZoom to 100", () => {
    const kt = mainActivity;
    for (const s of ["setSupportZoom(false)", "builtInZoomControls = false", "displayZoomControls = false", "textZoom = 100"]) expect(kt).toContain(s);
  });
});
