// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
import path from "node:path";
import process from "node:process";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

const host = process.env.TAURI_DEV_HOST;

// The daemon the browser build talks to in development. The daemon refuses a browser Origin that does not match
// its own Host (CONTRACT §7), so dev and tests reach it through this same-origin proxy, which drops the Origin
// header: the daemon then sees a plain local client. The Tauri build connects from Rust and needs no proxy.
// TESTENV-3: no default port. 8787 is the owner's daemon; a dev server or test must name its daemon explicitly.
const daemon = process.env.INFANTRY_URL ?? (process.env.INFANTRY_PORT ? `http://127.0.0.1:${process.env.INFANTRY_PORT}` : undefined);
const daemonHttp = (daemon ?? "").replace(/^ws/, "http").replace(/\/ws$/, "");

export default defineConfig(({ command }) => {
  // The build needs no daemon; only the dev server (serve) proxies to one.
  if (command === "serve" && !daemon) {
    throw new Error("vite: set INFANTRY_URL (e.g. ws://127.0.0.1:18990/ws) or INFANTRY_PORT to the daemon this dev server proxies to; there is no default.");
  }
  return {
  plugins: [react(), tailwindcss()],
  resolve: {
    // The workspace packages resolve by path, so the build needs no node_modules links (Windows installs are
    // link-free: nodeLinker hoisted, symlink false). Same as the tsconfig paths.
    alias: [
      { find: /^@infantry\/ui\/styles\.css$/, replacement: path.resolve(__dirname, "../packages/ui/src/styles.css") },
      { find: /^@infantry\/ui$/, replacement: path.resolve(__dirname, "../packages/ui/src/index.ts") },
      { find: /^@infantry\/client$/, replacement: path.resolve(__dirname, "../packages/client/src/index.ts") },
      { find: /^@infantry\/ui-plugin-api\/contract$/, replacement: path.resolve(__dirname, "../packages/ui-plugin-api/src/contract.ts") },
      { find: /^@infantry\/ui-plugin-api\/layout$/, replacement: path.resolve(__dirname, "../packages/ui-plugin-api/src/layout.ts") },
      { find: "@", replacement: path.resolve(__dirname, "./src") },
    ],
  },
  clearScreen: false,
  server: {
    port: Number(process.env.INFANTRY_UI_PORT ?? 1420),
    strictPort: true,
    host: host || false,
    hmr: host ? { protocol: "ws", host, port: 1421 } : undefined,
    watch: { ignored: ["**/src-tauri/**"] },
    proxy: {
      "/ws": {
        target: daemonHttp,
        ws: true,
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on("proxyReqWs", (req) => req.removeHeader("origin"));
          proxy.on("proxyReq", (req) => req.removeHeader("origin"));
        },
      },
      // Web UI plugins' files (WEBUI-1): the browser host reads them like the socket, same-origin, with no Origin header.
      "/ui-plugins": {
        target: daemonHttp,
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on("proxyReq", (req) => req.removeHeader("origin"));
        },
      },
    },
  },
  };
});
