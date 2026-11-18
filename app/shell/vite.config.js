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
const daemon = process.env.INFANTRY_URL ?? `http://127.0.0.1:${process.env.INFANTRY_PORT ?? "8787"}`;
const daemonHttp = daemon.replace(/^ws/, "http").replace(/\/ws$/, "");
export default defineConfig(() => ({
    plugins: [react(), tailwindcss()],
    resolve: {
        alias: { "@": path.resolve(__dirname, "./src") },
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
        },
    },
}));
