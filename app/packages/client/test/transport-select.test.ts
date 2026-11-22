import { describe, expect, it } from "vitest";
import { chooseTransport, parseTransportSetting, withFallback, type Transport, type TransportFactory, type TransportHandlers } from "../src";

describe("transport selection", () => {
  it("defaults to direct, plugin only when asked", () => {
    expect(parseTransportSetting(undefined)).toBe("direct");
    expect(parseTransportSetting("")).toBe("direct");
    expect(parseTransportSetting(" Plugin ")).toBe("plugin");
    expect(parseTransportSetting("direct")).toBe("direct");
  });
  it("keeps the plugin for a cleartext ws url on an https page (Android), else direct", () => {
    expect(chooseTransport("direct", "ws://127.0.0.1:18990/ws", "http:")).toBe("direct");
    expect(chooseTransport("direct", "ws://100.1.2.3:18990/ws", "https:")).toBe("plugin");
    expect(chooseTransport("direct", "wss://host/ws", "https:")).toBe("direct");
    expect(chooseTransport("plugin", "ws://127.0.0.1:1/ws", "http:")).toBe("plugin");
  });
});

function fake(log: string[], name: string, failOpen: boolean): { f: TransportFactory; made: Transport[] } {
  const made: Transport[] = [];
  const f: TransportFactory = () => {
    let h!: TransportHandlers;
    const t: Transport = {
      open(x) {
        h = x;
        log.push(`${name}.open`);
        queueMicrotask(() => (failOpen ? h.onClose("refused") : h.onOpen()));
      },
      send: (s) => void log.push(`${name}.send:${s}`),
      close: () => void log.push(`${name}.close`),
    };
    made.push(t);
    return t;
  };
  return { f, made };
}

describe("withFallback", () => {
  it("uses the plugin once the direct socket fails before opening, and keeps it", async () => {
    const log: string[] = [];
    const d = fake(log, "direct", true);
    const p = fake(log, "plugin", false);
    const fb: string[] = [];
    const f = withFallback(d.f, p.f, (r) => fb.push(r));
    const t = f();
    let opened = 0;
    t.open({ onOpen: () => opened++, onText: () => {}, onClose: () => {} });
    await Promise.resolve();
    await Promise.resolve();
    expect(opened).toBe(1);
    expect(fb).toEqual(["refused"]);
    t.send("x");
    expect(log).toContain("plugin.send:x");
    f(); // the next socket goes straight to the plugin
    expect(p.made.length).toBe(2);
    expect(d.made.length).toBe(1);
  });
  it("passes a close after a good open through (a reconnect is not a fallback)", async () => {
    const log: string[] = [];
    const d = fake(log, "direct", false);
    const p = fake(log, "plugin", false);
    const t = withFallback(d.f, p.f)();
    const closes: string[] = [];
    t.open({ onOpen: () => {}, onText: () => {}, onClose: (r) => closes.push(r) });
    await Promise.resolve();
    expect(p.made.length).toBe(0);
    expect(closes).toEqual([]);
  });
});
