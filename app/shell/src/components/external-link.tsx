// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// A link that leaves the app. `target="_blank"` does nothing inside the Tauri webview, so the click goes through the host seam
// (`host.openExternal`: the system browser on desktop, a new tab elsewhere). The only place that writes `target="_blank"`.
import type { AnchorHTMLAttributes } from "react";
import { host } from "@/host";

export function ExternalLink({ href, onClick, ...rest }: AnchorHTMLAttributes<HTMLAnchorElement> & { href: string }) {
  return (
    <a
      {...rest}
      href={href}
      target="_blank"
      rel="noreferrer noopener"
      onClick={(e) => {
        onClick?.(e);
        if (e.defaultPrevented) return;
        e.preventDefault();
        void host.openExternal(href).catch(() => undefined);
      }}
    />
  );
}
