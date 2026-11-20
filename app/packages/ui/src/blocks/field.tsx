// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// A labelled form field with an optional hint line (moved into the kit by WEBUI-1).
import type { ReactNode } from "react";
import { Label } from "../components/label";

export function Field({ id, label, children, hint }: { id: string; label: string; children: ReactNode; hint?: string }) {
  return (
    <div className="space-y-1">
      <Label htmlFor={id}>{label}</Label>
      {children}
      {hint && <p className="text-[11px] text-muted-foreground">{hint}</p>}
    </div>
  );
}
