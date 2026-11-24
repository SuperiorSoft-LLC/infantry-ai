// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// BOS-26: a start that rejects shows its own message on the splash, in place of "Still connecting".
import { describe, expect, it, vi } from "vitest";
import { splashMessage, startFailed } from "../../../shell/src/splash";

const splash = () => {
  const el = { textContent: "Still connecting to the Infantry AI daemon...", hidden: true };
  return { el, doc: { getElementById: (id: string) => (id === "splash-msg" ? el : null) } as never };
};

describe("splash", () => {
  it("a rejected start shows its message and stops the 'Still connecting' timer", () => {
    vi.useFakeTimers();
    const { el, doc } = splash();
    const late = vi.fn();
    const timer = setTimeout(late, 8000);
    startFailed(new Error("os_app_status: the desktop did not answer"), timer, doc);
    expect(el.textContent).toBe("Could not start: os_app_status: the desktop did not answer");
    expect(el.hidden).toBe(false);
    vi.advanceTimersByTime(9000);
    expect(late).not.toHaveBeenCalled();
    vi.useRealTimers();
  });
  it("a string or object rejection is readable too, and a page without the splash does not throw", () => {
    const { el, doc } = splash();
    startFailed("refused", undefined, doc);
    expect(el.textContent).toBe("Could not start: refused");
    startFailed({ code: 3 }, undefined, doc);
    expect(el.textContent).toBe('Could not start: {"code":3}');
    expect(() => splashMessage("x", { getElementById: () => null } as never)).not.toThrow();
  });
});
